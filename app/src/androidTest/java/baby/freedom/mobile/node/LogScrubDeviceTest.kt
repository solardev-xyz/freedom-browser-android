package baby.freedom.mobile.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LogScrub] on Android's own regex engine (ICU), not the JVM's the unit
 * tests run on: ICU refuses some patterns the JVM takes (an unescaped `}`),
 * and a pattern that fails to compile takes the whole log reader down with
 * it (#276, R4-F1).
 */
@RunWith(AndroidJUnit4::class)
class LogScrubDeviceTest {
    private val cid = "QmT5NvUtoM5nWFfrQdVrFtvGfKFmG7AHE8P34isapyhCxX"

    @Test
    fun aPathWithACommaIsTakenOutWhole() {
        val line = """INFO gateway_request{request_id=2 path=/ipfs/$cid/alan%20probe%20secret,diary%20entry.html}: """ +
            """freedom_ipfs_gateway: phase="request_start" path=/ipfs/$cid/alan%20probe%20secret,diary%20entry.html method=GET"""
        val out = LogScrub.scrub(line)
        assertFalse(out, "diary" in out)
        assertEquals(
            """INFO gateway_request{request_id=2 path=<redacted>}: freedom_ipfs_gateway: phase="request_start" path=<redacted> method=GET""",
            out,
        )
    }

    @Test
    fun everyScrubPatternRunsOnTheDevice() {
        val line = """name="docs.ipfs.tech" error=dnslink record not found for docs.ipfs.tech _dnslink.docs.ipfs.tech """ +
            "ipfs://x /ipfs/$cid bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi peer_id=$cid " +
            "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
        val out = LogScrub.scrub(line)
        assertFalse(out, "docs" in out)
        assertFalse(out, "bafybei" in out)
        assertFalse(out, "duckduckgo" in out)
    }
}
