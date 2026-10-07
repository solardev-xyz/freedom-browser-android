package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.l10n.ResourceXmlStrings
import baby.freedom.mobile.l10n.StringSource
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.DuplicateAccountException
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.ledger.LedgerDevice
import baby.freedom.mobile.wallet.ledger.LedgerException
import baby.freedom.mobile.wallet.ledger.LedgerKey
import baby.freedom.mobile.wallet.ledger.LedgerScheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** How the wallet names a Ledger account and what the Ledger dialog says (#142). */
class LedgerUiTest {
    private val key = LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Nano X 1A2B")
    private val ledger = WalletAccount(-1, "Cold", "0x" + "11".repeat(20), key)
    private val software = WalletAccount(0, "Account 1", "0x" + "22".repeat(20))

    @Test
    fun `a Ledger account says where its key is, once`() {
        assertEquals("Cold · Ledger", accountLabel(ledger))
        assertEquals("Ledger 1", accountLabel(ledger.copy(name = "Ledger 1")))
        assertEquals("Account 1", accountLabel(software))
        assertEquals("On Ledger Nano X 1A2B · m/44'/60'/0'/0/0", accountPathLine(ledger))
        assertEquals("On Ledger Stax 9F · m/44'/60'/0'/0/0", accountPathLine(ledger.copy(ledger = key.copy(deviceName = "Ledger Stax 9F"))))
        assertEquals("Derivation path m/44'/60'/0'/0/0", accountPathLine(software))
    }

    @Test
    fun `a translation that writes Ledger its own way still says it once`() {
        // A build whose default Ledger name and suffix aren't in Latin letters (#313 R1-M4).
        val english = ResourceXmlStrings()
        Strings.useForTest(object : StringSource {
            override fun string(id: Int, vararg args: Any?): String = when (id) {
                R.string.wallet_account_default_ledger_name -> "Леджер %1\$d".format(*args)
                R.string.wallet_accounts_label_ledger -> "%1\$s · Леджер".format(*args)
                else -> english.string(id, *args)
            }
            override fun plural(id: Int, count: Int, vararg args: Any?): String = english.plural(id, count, *args)
        })
        try {
            assertEquals("Леджер 2", accountLabel(ledger.copy(name = "Леджер 2")))
            assertEquals("Ledger 1", accountLabel(ledger.copy(name = "Ledger 1")))
            assertEquals("Cold · Леджер", accountLabel(ledger))
            assertEquals("Леджер 2 Cold · Леджер", accountLabel(ledger.copy(name = "Леджер 2 Cold")))
        } finally {
            Strings.useForTest(null)
        }
    }

    @Test
    fun `only a Ledger account's signing asks name the Ledger`() {
        val chain = BuiltInChains.ALL.first { it.id == 100L }
        assertEquals(key, ledgerOf(EthAsk.SignMessage("https://a.example", ledger, "hi", "0x6869")))
        assertNull(ledgerOf(EthAsk.SignMessage("https://a.example", software, "hi", "0x6869")))
        assertNull(ledgerOf(EthAsk.Connect("https://a.example", chain)))
    }

    @Test
    fun `the dialog says what the Ledger is waiting for`() {
        fun text(stage: Ledger.Stage) = ledgerActivityText(Ledger.Activity("Nano X", stage, "Confirm the message on your Ledger") {}).first
        assertEquals("Unlock your Ledger", text(Ledger.Stage.UNLOCK))
        assertEquals("Open the Ethereum app", text(Ledger.Stage.OPEN_APP))
        assertEquals("Pair with your Ledger", text(Ledger.Stage.PAIRING))
        assertEquals("Allow USB access", text(Ledger.Stage.USB_PERMISSION))
        assertEquals("Confirm the message on your Ledger", text(Ledger.Stage.CONFIRM))
    }

    @Test
    fun `checking an address says to compare it with the Ledger's screen (#365)`() {
        val a = Ledger.Activity("Nano X", Ledger.Stage.CONFIRM, "Check the address on your Ledger and confirm", "0x" + "ab".repeat(20)) {}
        val (title, detail) = ledgerActivityText(a)
        assertEquals("Check the address on your Ledger and confirm", title)
        assertTrue(detail, detail.contains("If it matches, approve it on the Ledger"))
        // A signature's confirmation keeps its own line.
        assertEquals(
            "Check the details on the Ledger’s screen, then approve or reject them there.",
            ledgerActivityText(a.copy(address = null)).second,
        )
    }

    @Test
    fun `an account is enrolled only once the Ledger confirmed its address and the user said it showed it (#365)`() = runBlocking {
        val path = "44'/60'/0'/0/0"
        val address = "0x" + "ab".repeat(20)
        val steps = ArrayList<String>()
        enrolConfirmed(
            path,
            address,
            confirm = { p, a -> steps += "confirm $p $a" },
            attest = { a -> steps += "attest $a"; true },
            enrol = { p, a -> steps += "enrol $p $a" },
        )
        assertEquals(listOf("confirm $path $address", "attest $address", "enrol $path $address"), steps)
        for (kind in listOf(LedgerException.Kind.REJECTED, LedgerException.Kind.TIMEOUT, LedgerException.Kind.CANCELLED, LedgerException.Kind.WRONG_DEVICE)) {
            var enrolled = false
            var asked = false
            try {
                enrolConfirmed(path, address, confirm = { _, _ -> throw LedgerException(kind) }, attest = { asked = true; true }, enrol = { _, _ -> enrolled = true })
                fail("$kind ended as added")
            } catch (e: LedgerException) {
                assertEquals(kind, e.kind)
            }
            assertEquals("$kind enrolled the account", false, enrolled)
            assertEquals("$kind asked the user anyway", false, asked)
        }
        // An impostor answers "approved" at once with nobody touching it (R1-F1):
        // a No on the phone still adds nothing.
        var enrolled = false
        try {
            enrolConfirmed(path, address, confirm = { _, _ -> }, attest = { false }, enrol = { _, _ -> enrolled = true })
            fail("added with no Yes on the phone")
        } catch (e: AddressNotAttestedException) {
            assertEquals("You didn’t confirm that your Ledger showed this address, so the account wasn’t added.", ledgerAddFailure(e))
        }
        assertEquals(false, enrolled)
    }

    @Test
    fun `Verify on Ledger passes only once the user says the Ledger showed the address (#365 R1-F1)`() = runBlocking {
        val address = "0x" + "ab".repeat(20)
        val steps = ArrayList<String>()
        verifyConfirmed(address, verify = { steps += "verify" }, attest = { steps += "attest $it"; true })
        assertEquals(listOf("verify", "attest $address"), steps)
        try {
            verifyConfirmed(address, verify = {}, attest = { false })
            fail("verified with no Yes on the phone")
        } catch (e: AddressNotAttestedException) {
            assertEquals(
                "Not verified. If your Ledger showed a different address, or showed nothing, don’t use this one to receive.",
                ledgerVerifyFailure(e),
            )
        }
        var asked = false
        try {
            verifyConfirmed(address, verify = { throw LedgerException(LedgerException.Kind.REJECTED) }, attest = { asked = true; true })
            fail("verified a rejection")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.REJECTED, e.kind)
        }
        assertEquals(false, asked)
    }

    @Test
    fun `a failed Add says the account wasn't added, and why (#365)`() {
        fun said(e: Exception) = ledgerAddFailure(e)
        assertEquals("Rejected on the Ledger, so the account wasn’t added.", said(LedgerException(LedgerException.Kind.REJECTED)))
        assertEquals("The Ledger didn’t confirm the address in time, so the account wasn’t added.", said(LedgerException(LedgerException.Kind.TIMEOUT)))
        assertEquals("Cancelled. The account wasn’t added.", said(LedgerException(LedgerException.Kind.CANCELLED)))
        assertEquals("This Ledger didn’t show that address, so the account wasn’t added.", said(LedgerException(LedgerException.Kind.WRONG_DEVICE)))
        assertEquals(
            "Your Ledger is locked. Unlock it with your PIN. The account wasn’t added.",
            said(LedgerException(LedgerException.Kind.LOCKED)),
        )
        // No "Nothing was signed." in an add's failure.
        for (kind in LedgerException.Kind.entries) assertTrue(kind.name, !said(LedgerException(kind)).contains("Nothing was"))
        assertEquals("That account is already in this wallet.", said(DuplicateAccountException()))
        assertEquals("Couldn’t add the account. The phone may be out of storage.", said(java.io.IOException("disk")))
    }

    @Test
    fun `Verify on Ledger says nothing for Cancel and warns on a rejection (#365)`() {
        assertNull(ledgerVerifyFailure(LedgerException(LedgerException.Kind.CANCELLED)))
        assertEquals(
            "Rejected on the Ledger. If it showed a different address, don’t use this one to receive.",
            ledgerVerifyFailure(LedgerException(LedgerException.Kind.REJECTED)),
        )
        assertEquals(LedgerException.Kind.WRONG_DEVICE.message, ledgerVerifyFailure(LedgerException(LedgerException.Kind.WRONG_DEVICE)))
        // The Ledger approved some other address: warn against the stored one (R1-F3).
        val different = try {
            Ledger.shownMatches("0x" + "11".repeat(20), "0x" + "ab".repeat(20))
            null
        } catch (e: LedgerException) {
            e
        }
        assertEquals(
            "The Ledger approved a different address from this one. Don’t use this one to receive.",
            ledgerVerifyFailure(different!!),
        )
        assertEquals(
            "The Ledger didn’t confirm the address in time. Verify again before you share it.",
            ledgerVerifyFailure(LedgerException(LedgerException.Kind.TIMEOUT)),
        )
        assertEquals(
            "The Ledger couldn’t show the address. Verify again before you share it.",
            ledgerVerifyFailure(LedgerException(LedgerException.Kind.INVALID_DATA)),
        )
        // Nothing is signed by a verify, so no signing line for any kind (R1-F3).
        for (kind in LedgerException.Kind.entries) {
            val said = ledgerVerifyFailure(LedgerException(kind)) ?: continue
            assertTrue(kind.name, !said.contains("Nothing was"))
        }
    }

    @Test
    fun `a found account is numbered from its path, not its row`() {
        // Ledger Live numbering: Account N is index N-1 of the layout, wherever the list starts.
        assertEquals(1, ledgerAccountNumber(LedgerScheme.LIVE, LedgerScheme.LIVE.path(0), row = 0))
        assertEquals(6, ledgerAccountNumber(LedgerScheme.LIVE, LedgerScheme.LIVE.path(5), row = 0))
        assertEquals(12, ledgerAccountNumber(LedgerScheme.LEGACY, LedgerScheme.LEGACY.path(11), row = 2))
        for (scheme in LedgerScheme.entries) for (i in listOf(0, 1, 9, 10, 123)) assertEquals(i, scheme.index(scheme.path(i)))
        // Not a path of that layout: the row is all there is.
        assertNull(LedgerScheme.LIVE.index(LedgerScheme.LEGACY.path(3)))
        assertNull(LedgerScheme.LIVE.index("44'/60'/01'/0/0"))
        assertEquals(3, ledgerAccountNumber(LedgerScheme.LIVE, "44'/60'/0'/7", row = 2))
    }

    @Test
    fun `two same-model USB Ledgers are told apart by their device number (R6-M1)`() {
        val a = LedgerDevice("usb:/dev/bus/usb/001/005", "Ledger Nano S Plus", paired = true)
        val b = LedgerDevice("usb:/dev/bus/usb/002/012", "Ledger Nano S Plus", paired = true)
        val x = LedgerDevice("usb:/dev/bus/usb/001/007", "Ledger Nano X", paired = true)
        assertEquals(1 to 5, usbDeviceNumber(a, listOf(a, b, x)))
        assertEquals(2 to 12, usbDeviceNumber(b, listOf(a, b, x)))
        assertNull(usbDeviceNumber(x, listOf(a, b, x)))
        assertNull(usbDeviceNumber(a, listOf(a, x)))
    }
}
