package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.PublisherIdentity
import baby.freedom.mobile.wallet.SitePublisher
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the publisher identities page (#119) says about sites and identities. */
class PublisherIdentitiesScreenTest {
    private val first = PublisherIdentity(PublisherIdentity.Mode.APP_SCOPED, 0, "App-scoped identity 1", 1)
    private val blog = PublisherIdentity(PublisherIdentity.Mode.APP_SCOPED, 4, "Blog", 2)

    @Test
    fun `the Wallet row counts sites`() {
        assertEquals("The keys sites sign their Swarm feeds with", publisherIdentitiesSummary(0))
        assertEquals("1 site", publisherIdentitiesSummary(1))
        assertEquals("3 sites", publisherIdentitiesSummary(3))
    }

    @Test
    fun `a site row names the active identity and counts them`() {
        val one = SitePublisher("https://a.example", first.id, listOf(first), 1)
        assertEquals("Publishes as App-scoped identity 1 · 1 identity", publisherSiteSummary(one))
        val two = one.copy(activeId = blog.id, identities = listOf(first, blog))
        assertEquals("Publishes as Blog · 2 identities", publisherSiteSummary(two))
    }

    @Test
    fun `an identity row gives its kind, not its key path`() {
        assertEquals("App-scoped", publisherIdentityDetail(blog))
        assertEquals("Ant wallet", publisherIdentityDetail(PublisherIdentity.antWallet()))
    }

    @Test
    fun `site rows open while the wallet is locked, only not mid-action`() {
        assertEquals(true, publisherSiteRowEnabled(busy = false))
        assertEquals(false, publisherSiteRowEnabled(busy = true))
    }
}
