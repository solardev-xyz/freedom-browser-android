package baby.freedom.mobile.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Neither cloud backup nor device-to-device transfer copies a store that
 * lets a site act on the wallet or node without asking (#229): on a new
 * phone those would come back silently once the same phrase is imported.
 */
class BackupRulesTest {
    private val res = File("src/main/res/xml")
    private val sources = File("src/main/java/baby/freedom/mobile/data")

    /** Each store's DataStore file name, checked against its source so a rename can't slip past the rules. */
    private val stores = mapOf(
        "RpcKeyStore.kt" to "freedom_rpc_secrets",
        "DappGrantStore.kt" to "freedom_dapp_grants",
        "AutoApproveStore.kt" to "freedom_dapp_auto_approve",
        "X402Store.kt" to "freedom_x402",
        "SwarmGrantStore.kt" to "freedom_swarm_grants",
        // A signing grant names the wallet's Radicle DID, which the same phrase derives again (#336).
        "RadicleGrantStore.kt" to "freedom_radicle_grants",
    )

    /** Plain files beside them: the Swarm manifests' record of which Swarm grants they own. */
    private val files = mapOf(
        "../browser/SwarmProviderBridge.kt" to "swarm-manifests.json",
    )

    /**
     * The nodes' own device-only keys (no wallet identity): ant's identity
     * under the data dir NodeService hands it, and the Radicle profile's
     * key pair. A copy would run one node identity on two phones.
     */
    private val nodeKeys = mapOf(
        "../node/NodeService.kt" to listOf("dataDir = filesDir.absolutePath" to "ant/identity.json"),
        "../node/NodeService.kt#radicle" to listOf("home = filesDir.resolve(\"radicle\")" to "radicle/keys"),
    )

    private fun excludes(file: String, section: String?): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, file))
        val root = if (section == null) doc.documentElement else doc.getElementsByTagName(section).item(0) as Element
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test
    fun `grant, auto-approve, x402, API key, manifest and node key stores are excluded from every backup and transfer`() {
        for ((source, name) in stores) {
            assertTrue("$source names its store $name", File(sources, source).readText().contains("\"$name\""))
        }
        for ((source, name) in files) {
            assertTrue("$source names its file $name", File(sources, source).readText().contains("filesDir, \"$name\")"))
        }
        for ((source, pins) in nodeKeys) {
            val text = File(sources, source.substringBefore('#')).readText()
            for ((pin, _) in pins) assertTrue("$source still has $pin", text.contains(pin))
        }
        val paths = stores.values.map { "datastore/$it.preferences_pb" } + files.values +
            nodeKeys.values.flatten().map { it.second }
        for ((file, section) in listOf(
            "data_extraction_rules.xml" to "cloud-backup",
            "data_extraction_rules.xml" to "device-transfer",
            "backup_rules.xml" to null,
        )) {
            val excluded = excludes(file, section)
            for (p in paths) assertTrue("$file ${section ?: ""} excludes $p", p in excluded)
        }
    }
}
