package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.ledger.LedgerKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("Confirm the message on your Ledger", text(Ledger.Stage.CONFIRM))
    }
}
