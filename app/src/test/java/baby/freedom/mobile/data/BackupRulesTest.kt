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
    fun `grant, auto-approve, x402 and API key stores are excluded from every backup and transfer`() {
        for ((source, name) in stores) {
            assertTrue("$source names its store $name", File(sources, source).readText().contains("\"$name\""))
        }
        val paths = stores.values.map { "datastore/$it.preferences_pb" }
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
