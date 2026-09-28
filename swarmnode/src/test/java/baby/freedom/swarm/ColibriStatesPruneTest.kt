package baby.freedom.swarm

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The verifier's state directory keeps only its consensus state (#100,
 * PR #205 R2-F1): a per-contract `call_*` cache names every resolved
 * name's registry slot and contenthash, so it must not survive [ColibriNative.init].
 */
class ColibriStatesPruneTest {
    @Test
    fun `only consensus state and the version marker survive`() {
        val dir = Files.createTempDirectory("colibri").toFile()
        val keep = listOf("states_1", "sync_1_1650", "sync_1_1650.tmp", "rdelay_prover_1", "freedom-colibri-storage-version")
        val drop = listOf("call_1_00000000000c2e074ec69a0dfb2997ba6c7d2e1e", "code_ab12", "headers_1", "tx_cache_1", "unknown")
        (keep + drop).forEach { dir.resolve(it).writeText("x") }

        ColibriNative.pruneStates(dir)

        assertEquals(keep.sorted(), dir.list()!!.sorted())
        dir.deleteRecursively()
    }
}
