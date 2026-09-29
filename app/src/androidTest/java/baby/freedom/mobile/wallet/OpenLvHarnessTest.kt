package baby.freedom.mobile.wallet

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.MainActivity
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.ens.hexToBytes
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Desktop Freedom's remote signing (#113) end to end, against a desktop
 * host: `scripts/openlv-android-harness.js` (desktop's own openlv bundle
 * in a headless Chromium, over a local MQTT broker) mints the code, this
 * joins it through the real [WebViewOpenLvEngine], and the request shows
 * on the app's real sheet over [MainActivity].
 *
 * Skipped unless run by hand with the harness's code:
 *
 *     adb reverse tcp:8720 tcp:8720
 *     adb shell am instrument -w -e class baby.freedom.mobile.wallet.OpenLvHarnessTest \
 *         -e uri 'openlv://…' [-e approve auto] baby.freedom.mobile.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The session signs with the key in the app's `files/openlv-harness.key`
 * (hex, put there with `run-as`), deleted as soon as it's read — for an
 * account funded on a real chain, where the wallet's own phrase-derived
 * accounts have nothing. Everything else is the app's: the engine, the
 * send flow ([WalletSender] and its RPC routing), and the sheets, which
 * wait for a tap on the device unless `approve` is `auto`.
 */
@RunWith(AndroidJUnit4::class)
class OpenLvHarnessTest {
    @Test
    fun answersADesktopJob() {
        val args = InstrumentationRegistry.getArguments()
        val uri = args.getString("uri")
        assumeTrue("run by hand with -e uri (see the class docs)", uri != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyFile = File(context.filesDir, "openlv-harness.key")
        assumeTrue("put the key in files/openlv-harness.key first", keyFile.exists())
        val key = try {
            keyFile.readText().trim().hexToBytes()
        } finally {
            keyFile.delete()
        }
        val address = NodeIdentity.checksum(
            baby.freedom.mobile.ens.Keccak256.digest(Secp256k1Keys.publicKeyUncompressed(key)).copyOfRange(12, 32),
        )
        val account = WalletAccount(0, "Harness account", address)
        val keys = object : OpenLvSession.Keys {
            override fun accounts() = WalletAccountList(listOf(account), 0)
            override fun <T> withKey(account: WalletAccount, block: (ByteArray) -> T): T {
                val copy = key.copyOf()
                return try {
                    block(copy)
                } finally {
                    copy.fill(0)
                }
            }
            override fun noteActivity() = Unit
        }
        val chainStore = ChainStore.get(context)
        val session = OpenLvSession(
            engine = WebViewOpenLvEngine(context),
            keys = keys,
            chains = { chainStore.chains.first() },
            sender = WalletSender.get(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        // The app's sheets show whatever session OpenLvSession.get hands them: this one.
        OpenLvSession::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, session)
        val auto = args.getString("approve") == "auto"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { session.start(uri!!) }
                runBlocking {
                    withTimeout(300_000) {
                        var answered = 0
                        var last: OpenLvSession.Approval? = null
                        // Until desktop closes the session with every sheet answered.
                        while (!session.status.value.ended() || session.approval.value != null) {
                            val open = session.approval.value
                            if (open != null && open !== last) {
                                last = open
                                answered++
                                if (auto) open.decide(OpenLvSession.Decision.Approve())
                            }
                            delay(100)
                        }
                        assertTrue("desktop asked for nothing (${session.status.value})", answered > 0)
                    }
                }
            }
        } finally {
            key.fill(0)
            OpenLvSession::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun OpenLvSession.Status.ended() = this == OpenLvSession.Status.Disconnected || this is OpenLvSession.Status.Failed
}
