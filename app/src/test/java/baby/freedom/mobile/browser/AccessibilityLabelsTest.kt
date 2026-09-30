package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What TalkBack says of the browser chrome's controls whose drawn
 * content is no label at all (#279): the tab counter's digit, the
 * address bar's tap surface, the load trace on its outline.
 */
class AccessibilityLabelsTest {

    @Test
    fun `the tabs button names itself and says the count in words`() {
        assertEquals("Tabs, 1 open", tabsCountDescription(1))
        assertEquals("Tabs, 3 open", tabsCountDescription(3))
        // The badge draws ∞ past 99; the label still has the number.
        assertEquals("Tabs, 120 open", tabsCountDescription(120))
    }

    @Test
    fun `the address bar is named, with the placeholder on the home tab`() {
        assertEquals("Address bar", addressBarDescription(private = false, empty = false))
        assertEquals(
            "Address bar, search or type URL",
            addressBarDescription(private = false, empty = true),
        )
    }

    @Test
    fun `a private tab's address bar says so`() {
        assertEquals("Address bar, private tab", addressBarDescription(private = true, empty = false))
        assertEquals(
            "Address bar, private tab, search or type URL",
            addressBarDescription(private = true, empty = true),
        )
    }

    @Test
    fun `a load is said as its phase, in tens of percent`() {
        assertEquals("Resolving name", capsuleLoadStateDescription(resolving = true, tenths = 3))
        assertEquals("Loading, 0%", capsuleLoadStateDescription(resolving = false, tenths = 0))
        assertEquals("Loading, 70%", capsuleLoadStateDescription(resolving = false, tenths = 7))
    }

    @Test
    fun `the placeholder's wordings run longest first and all say search`() {
        assertEquals("Search or type URL", AddressPlaceholders.first())
        assertEquals(AddressPlaceholders.sortedByDescending { it.length }, AddressPlaceholders)
        AddressPlaceholders.forEach { assert(it.startsWith("Search")) }
    }
}
