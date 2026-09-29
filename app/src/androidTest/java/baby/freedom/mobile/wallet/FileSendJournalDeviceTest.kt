package baby.freedom.mobile.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.math.BigInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [FileSendJournal] on the real filesystem: the directory fsync that
 * makes a save survive a power loss goes through `android.system.Os`,
 * which a JVM test only sees as a stub.
 */
@RunWith(AndroidJUnit4::class)
class FileSendJournalDeviceTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "send-journal-test")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theDirectorySyncsAndASaveReadsBack() {
        dir.mkdirs()
        assertTrue(FileSendJournal.syncDirectory(dir))
        assertFalse(FileSendJournal.syncDirectory(File(dir, "missing")))

        val file = File(dir, "wallet/send.json")
        val journal = FileSendJournal(file)
        val abandoned = NonceTracker.Abandoned(BigInteger.valueOf(7), EthTransaction.Fees.Legacy(BigInteger.TEN), "0x01")
        assertTrue(journal.save(SendJournal.State(null, mapOf("100:0xaa" to abandoned))))
        assertFalse(File(file.parentFile, "send.json.tmp").exists())
        val back = journal.load()!!
        assertNull(back.send)
        assertEquals(BigInteger.valueOf(7), back.abandoned.getValue("100:0xaa").nonce)

        assertTrue(journal.save(SendJournal.State(null, emptyMap())))
        assertFalse(file.exists())
    }
}
