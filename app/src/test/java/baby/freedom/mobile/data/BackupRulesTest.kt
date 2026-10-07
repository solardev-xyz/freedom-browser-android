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

    private val root = File("..")

    /**
     * The embedded nodes' data dirs (#338), each pinned to the source line
     * that names it: ant's dir and SwarmNode's deposit hold (and its
     * `.tmp`) under the data dir NodeService hands the Swarm node, the
     * Radicle profile, freedom-ipfs and Arti. Each holds a node identity
     * (ant's plain-text device key, the Radicle profile key) or state tied
     * to one, so a copy would run one node on two phones.
     */
    private val nodeDirs = listOf(
        // filesDir itself, which SwarmNode sees as `config.dataDir`: see [filesDirAliases].
        Pin("app/src/main/java/baby/freedom/mobile/node/NodeService.kt", "dataDir = filesDir.absolutePath", null),
        Pin("swarmnode/src/main/java/baby/freedom/swarm/SwarmNode.kt", "config.dataDir + \"/ant\"", "ant"),
        Pin(
            "swarmnode/src/main/java/baby/freedom/swarm/SwarmNode.kt",
            "File(config.dataDir, UNCONFIRMED_DEPOSIT_FILE)",
            "unconfirmed-deposit.json",
        ),
        Pin(
            "swarmnode/src/main/java/baby/freedom/swarm/SwarmNode.kt",
            "File(config.dataDir, \"\$UNCONFIRMED_DEPOSIT_FILE.tmp\")",
            "unconfirmed-deposit.json.tmp",
        ),
        Pin("app/src/main/java/baby/freedom/mobile/node/NodeService.kt", "home = filesDir.resolve(\"radicle\")", "radicle"),
        Pin("app/src/main/java/baby/freedom/mobile/node/NodeService.kt", "dataDir = filesDir.resolve(\"ipfs\")", "ipfs"),
        Pin("app/src/main/java/baby/freedom/mobile/node/TorService.kt", "TorNode(filesDir.resolve(\"tor\"))", "tor"),
    )

    /** The deposit hold's file name, behind the constant the pins above use. */
    private val nodeNames = listOf(
        "swarmnode/src/main/java/baby/freedom/swarm/SwarmNode.kt" to
            "UNCONFIRMED_DEPOSIT_FILE = \"unconfirmed-deposit.json\"",
    )

    /**
     * Names a source uses for `filesDir` itself, scanned like `filesDir`:
     * NodeService hands SwarmNode `filesDir` as its data dir (the null pin
     * above), so a file SwarmNode puts at `config.dataDir` lands at the
     * root of `files/`.
     */
    private val filesDirAliases = mapOf(
        "swarmnode/src/main/java/baby/freedom/swarm/SwarmNode.kt" to Regex("""(?<![A-Za-z])config\.dataDir(?![A-Za-z])"""),
    )

    /** `filesDir`, and Java's `getFilesDir()`; not `noBackupFilesDir`. */
    private val filesDirUse = Regex("""(?i)(?<![a-z])(?:get)?filesDir(?![a-z])""")

    /** The key and identity files inside them, as seen on a device (#338): none may travel. */
    private val nodeKeyFiles = listOf(
        "ant/identity.json",
        "ant/account.json",
        "ant/accounts/0x0000000000000000000000000000000000000000/uploads",
        "ant/chunks.sqlite",
        "unconfirmed-deposit.json",
        "unconfirmed-deposit.json.tmp",
        "radicle/keys/radicle",
        "radicle/keys/radicle.pub",
        "radicle/node/node.db",
        "ipfs/blocks",
        "tor/state/state.json",
        "tor/cache/dir.sqlite3",
    )

    /**
     * Everything else the app keeps under `filesDir`, reviewed as carrying
     * no key: these may travel. A new `filesDir` user that's in neither
     * list fails [every filesDir user is either excluded or reviewed as
     * keyless], so a new node can't slip its key into a transfer.
     */
    private val keyless = listOf(
        // Name-independent sync-committee state (#205's allowlist).
        "app/src/main/java/baby/freedom/mobile/MainActivity.kt" to "File(filesDir, \"colibri\")",
        "app/src/main/java/baby/freedom/mobile/chains/rpc/ChainDataRouter.kt" to
            "File(app.filesDir, if (suffix.isEmpty()) \"colibri\" else \"colibri-\$suffix\")",
        // The light client's chain sync state.
        "app/src/main/java/baby/freedom/mobile/node/MyotisService.kt" to "filesDir.resolve(\"myotis\")",
        "app/src/main/java/baby/freedom/mobile/browser/Adblock.kt" to "File(app.filesDir, \"adblock\")",
        "app/src/main/java/baby/freedom/mobile/browser/AppUpdates.kt" to "File(app.filesDir, \"app-update\")",
        "app/src/main/java/baby/freedom/mobile/browser/Publish.kt" to "filesDir, \"publish/history.json\")",
        // Excluded above, with the grants it owns.
        "app/src/main/java/baby/freedom/mobile/browser/SwarmProviderBridge.kt" to "File(app.filesDir, \"swarm-manifests.json\")",
    )

    private data class Pin(val source: String, val text: String, val path: String?)

    private val rules = listOf(
        "data_extraction_rules.xml" to "cloud-backup",
        "data_extraction_rules.xml" to "device-transfer",
        "backup_rules.xml" to null,
    )

    /** Whether [path] is excluded, by itself or by an excluded directory above it. */
    private fun Set<String>.covers(path: String) = any { path == it || path.startsWith("$it/") }

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
    fun `grant, auto-approve, x402, API key and manifest stores are excluded from every backup and transfer`() {
        for ((source, name) in stores) {
            assertTrue("$source names its store $name", File(sources, source).readText().contains("\"$name\""))
        }
        for ((source, name) in files) {
            assertTrue("$source names its file $name", File(sources, source).readText().contains("filesDir, \"$name\")"))
        }
        val paths = stores.values.map { "datastore/$it.preferences_pb" } + files.values
        for ((file, section) in rules) {
            val excluded = excludes(file, section)
            for (p in paths) assertTrue("$file ${section ?: ""} excludes $p", p in excluded)
        }
    }

    @Test
    fun `the nodes' data dirs and the keys in them are excluded from every backup and transfer`() {
        for ((source, text) in nodeDirs.map { it.source to it.text } + nodeNames) {
            assertTrue("$source still has $text", File(root, source).readText().contains(text))
        }
        val dirs = nodeDirs.mapNotNull { it.path }
        for ((file, section) in rules) {
            val excluded = excludes(file, section)
            for (p in dirs + nodeKeyFiles) assertTrue("$file ${section ?: ""} excludes $p", excluded.covers(p))
        }
    }

    @Test
    fun `every filesDir user is either excluded or reviewed as keyless`() {
        val known = nodeDirs.map { it.source to it.text } + keyless
        for ((source, text) in keyless) {
            assertTrue("$source still has $text", File(root, source).readText().contains(text))
        }
        val users = listOf("app/src/main/java", "swarmnode/src/main/java").flatMap { dir ->
            File(root, dir).walk().filter { it.extension == "kt" || it.extension == "java" }.flatMap { f ->
                val rel = f.relativeTo(root).path
                val alias = filesDirAliases[rel]
                f.readLines()
                    .filter { filesDirUse.containsMatchIn(it) || alias?.containsMatchIn(it) == true }
                    .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                    .map { rel to it.trim() }
            }.toList()
        }
        // Each use is exactly one reviewed line, and each reviewed line
        // covers exactly one use: a second node handed filesDir the same
        // way as ant (`dataDir = filesDir.absolutePath`) can't pass as it.
        val matched = mutableMapOf<Pair<String, String>, Int>()
        for ((source, line) in users) {
            val hits = known.filter { (s, t) -> s == source && line.contains(t) }
            assertTrue(
                "$source uses filesDir in `$line`: exclude it in the backup rules or list it as keyless",
                hits.size == 1,
            )
            matched.merge(hits.single(), 1, Int::plus)
        }
        for (pin in known) {
            assertTrue(
                "${pin.first}: `${pin.second}` should be one filesDir use, found ${matched[pin] ?: 0}",
                matched[pin] == 1,
            )
        }
    }
}
