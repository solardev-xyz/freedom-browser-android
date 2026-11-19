package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainAccessPolicy
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ProofTierGap
import baby.freedom.mobile.l10n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four network sources named in plain words, as desktop's
 * `NETWORK_SOURCE_COPY` names them (#548, desktop #549), with the
 * technical name still on the page under Advanced.
 */
class NetworkSourceCopyTest {
    private val jargon = Regex(
        "quorum|prover|light client|P2P|\\bRPCs?\\b|Myotis|Colibri|endpoint|beacon|finalized|byte-identical",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun `labels are desktop's, in plain words`() {
        assertEquals(
            listOf("Local node", "Proof check", "Several servers must agree", "One server, unchecked"),
            ChainSource.entries.map { it.label },
        )
        for (source in ChainSource.entries) {
            assertFalse(source.label, jargon.containsMatchIn(source.label))
        }
    }

    @Test
    fun `the technical name leads each source's Advanced text`() {
        val technical = mapOf(
            ChainSource.MYOTIS to "Myotis — ",
            ChainSource.COLIBRI to "Colibri — ",
            ChainSource.QUORUM to "RPC quorum — ",
            ChainSource.DIRECT to "Direct RPC — ",
        )
        for ((source, name) in technical) {
            assertTrue(source.advanced, source.advanced.startsWith(name))
        }
    }

    @Test
    fun `the Proof check switch says it in plain words and keeps Colibri under Advanced`() {
        val help = Strings.get(R.string.names_colibri_help)
        assertFalse(help, jargon.containsMatchIn(help))
        // The privacy fact stays visible, not only under the fold.
        assertTrue(help, "learns the addresses" in help)
        assertTrue(Strings.get(R.string.names_colibri_advanced).startsWith("Colibri — "))
        val row = ensSectionRows(baby.freedom.mobile.ens.EnsRpcConfig()).single { it.key == "colibri" }
        assertEquals("Proof check", row.texts.first())
        assertTrue(row.texts.any { it.startsWith("Colibri — ") })
    }

    @Test
    fun `name resolution's intro and the chain list's statuses use plain words`() {
        val about = Strings.get(R.string.names_ens_about)
        assertFalse(about, Regex("light client|P2P|\\bRPC\\b|endpoint").containsMatchIn(about))
        for (res in listOf(R.string.names_chain_status_yours, R.string.names_chain_status_yours_fallback)) {
            val status = Strings.get(res)
            assertFalse(status, jargon.containsMatchIn(status))
        }
        for (res in listOf(R.string.settings_page_names_colibri_on, R.string.settings_page_names_colibri_off)) {
            val summary = Strings.get(res)
            assertFalse(summary, jargon.containsMatchIn(summary))
            assertTrue(summary, "chain reads" in summary)
        }
    }

    @Test
    fun `each read step's technical name is its own source's`() {
        val eth = BuiltInChains.ETHEREUM
        val policy = ChainAccessPolicy.default(eth.id).sanitized(eth.id)
        for (wired in listOf<(ChainSource) -> Boolean>(
            { true },
            tierAnswers(mapOf(ChainSource.MYOTIS to ProofTierGap.NOT_READY)),
            { it == ChainSource.QUORUM || it == ChainSource.DIRECT },
        )) {
            val steps = readSteps(eth, policy, wired)
            val sources = readStepSources(policy, wired)
            assertEquals(steps.size, sources.size)
            steps.zip(sources).forEach { (step, source) -> assertTrue(step, step.startsWith(source.label + ":")) }
        }
        // The two proof steps say how each proves, not the label again.
        val all = readSteps(eth, policy) { true }
        assertEquals("Local node: a proof checked on this device", all[0])
        assertEquals("Proof check: a server answers, and this device checks its proof", all[1])
    }
}
