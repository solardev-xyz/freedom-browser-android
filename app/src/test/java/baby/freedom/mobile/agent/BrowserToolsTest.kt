package baby.freedom.mobile.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The browser tools (#575) against a fake browser: tab ownership,
 * refusal of private and internal tabs, and that every interaction runs
 * only once its approval came back for that exact request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserToolsTest {
    private class FakeHost : AgentBrowserHost {
        val tabs = LinkedHashMap<Long, AgentTab>()
        var nextId = 10L
        val taps = ArrayList<Pair<Float, Float>>()
        val navigations = ArrayList<Pair<Long, String>>()
        val scripts = ArrayList<String>()
        var fronted = ArrayList<Long>()
        var canFront = true

        /** The control the page's describe/point answers name. */
        var control = JSONObject().put("role", "button").put("tag", "button").put("name", "Search")

        /** Run as each page script goes in, with its op. */
        var onScript: (String) -> Unit = {}

        /** Run when the tool brings the tab to the front, before the action. */
        var onFront: () -> Unit = {}

        fun add(tab: AgentTab) {
            tabs[tab.id] = tab
        }

        override fun tabs() = tabs.values.toList()
        var pendingCommit: Pair<Long, String>? = null

        override fun tab(id: Long): AgentTab? {
            val p = pendingCommit
            if (p != null && p.first == id && reads++ > 0) {
                tabs[id] = AgentTab(id, p.second, "", loading = false, private = false, internal = false, document = 1, active = false)
                pendingCommit = null
            }
            return tabs[id]
        }

        private var reads = 0
        override fun openTab(url: String): Long {
            val id = nextId++
            // Committed by the time the tool reads it again.
            tabs[id] = AgentTab(id, "", "", loading = false, private = false, internal = true, document = 0, active = false)
            pendingCommit = id to url
            return id
        }
        override fun navigate(id: Long, url: String) {
            navigations += id to url
            tabs[id]?.let { tabs[id] = it.copy(url = url, internal = false, document = it.document + 1) }
        }
        override fun close(id: Long) {
            tabs.remove(id)
        }
        override fun stop(id: Long) = Unit

        override suspend fun evaluate(id: Long, script: String, timeoutMs: Long): String {
            scripts += script
            val op = script.substringAfter("PAGE(\"").substringBefore('"')
            onScript(op)
            return when (op) {
                "snapshot" -> JSONObject()
                    .put("url", tabs[id]!!.url)
                    .put("elements", JSONArray().put(JSONObject(control.toString()).put("ref", tokenOf(script) + "_0")))
                    .put("fingerprints", JSONArray().put(control.toString()))
                    .toString()
                "describe" -> JSONObject().put("ok", true).put("target", control).toString()
                "point" -> JSONObject().put("ok", true).put("x", 100).put("y", 200).toString()
                "scroll", "scrollState" -> JSONObject().put("ok", true)
                    .put("scroll", JSONObject().put("x", 0).put("y", if (op == "scroll") 0 else 800)).toString()
                else -> JSONObject().put("ok", true).toString()
            }
        }

        private fun tokenOf(script: String) = JSONObject(script.substringAfter("\",").removeSuffix(")")).getString("token")

        override suspend fun bringToFront(id: Long): Boolean {
            fronted += id
            onFront()
            return canFront
        }

        /** Run between a tap's touch down and its lift. */
        var duringTap: () -> Unit = {}
        val cancelledTaps = ArrayList<Pair<Float, Float>>()

        override suspend fun tap(id: Long, x: Float, y: Float, proceed: () -> Boolean): Boolean {
            duringTap()
            if (!proceed()) {
                cancelledTaps += x to y
                return false
            }
            taps += x to y
            return true
        }

        val keys = ArrayList<Int>()
        override fun key(id: Long, keyCode: Int): Boolean {
            keys += keyCode
            return true
        }

        val commits = ArrayList<String>()

        /** What commitText answers, and what happens as it does. */
        var commitAnswer: (String) -> Boolean = { true }
        override suspend fun commitText(id: Long, text: String): Boolean {
            commits += text
            return commitAnswer(text)
        }
        var dialog: AgentDialog? = null
        val answers = ArrayList<Pair<Boolean, String?>>()
        override fun dialog(id: Long): AgentDialog? = dialog
        override fun answerDialog(id: Long, dialog: AgentDialog, accept: Boolean, promptText: String?): Boolean {
            if (this.dialog != dialog) return false
            answers += accept to promptText
            this.dialog = null
            return true
        }
    }

    private val host = FakeHost()
    private val asked = ArrayList<AgentApproval>()
    private var answer: (AgentApproval) -> ApprovalOutcome = { ApprovalOutcome.Approved }

    private fun toolset(session: String = "chat") = BrowserToolset(
        session,
        browser = { host },
        ask = { a -> asked += a; answer(a) },
        pageScript = { "PAGE" },
    )

    private fun page(id: Long, url: String = "example.com/", private: Boolean = false, internal: Boolean = false, document: Int = 3) =
        AgentTab(id, url, "Example", loading = false, private = private, internal = internal, document = document, active = false)

    private suspend fun BrowserToolset.call(name: String, args: JSONObject = JSONObject()): ToolOutcome =
        tools.first { it.spec.name == name }.run(args)

    private fun ToolOutcome.code() = output.substringAfter('[').substringBefore(']')

    private fun ToolOutcome.result() = JSONObject(output).getJSONObject("result")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AgentTabs.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AgentTabs.clear()
    }

    @Test
    fun `the tool names and schemas are desktop's`() {
        val names = toolset().tools.map { it.spec.name }
        assertEquals(
            listOf(
                "browser_get_dialog", "browser_handle_dialog", "browser_list_tabs", "browser_create_tab", "browser_get_tab",
                "browser_focus_tab", "browser_close_tab", "browser_snapshot", "browser_navigate", "browser_click",
                "browser_type", "browser_select", "browser_scroll", "browser_press", "browser_wait", "browser_stop_loading",
            ),
            names,
        )
        val click = toolset().tools.first { it.spec.name == "browser_click" }.spec.parameters
        assertEquals(listOf("ref"), (0 until click.getJSONArray("required").length()).map { click.getJSONArray("required").getString(it) })
        assertFalse(click.getBoolean("additionalProperties"))
    }

    @Test
    fun `only the tabs the chat holds are listed or reachable`() = runBlocking {
        host.add(page(1))
        host.add(page(2))
        AgentTabs.hold(1, "chat", private = false)
        AgentTabs.hold(2, "other", private = false)
        val tools = toolset()
        val listed = tools.call("browser_list_tabs").result().getJSONArray("tabs")
        assertEquals(1, listed.length())
        assertEquals("1", listed.getJSONObject(0).getString("tabId"))
        assertEquals("POLICY_DENIED", tools.call("browser_focus_tab", JSONObject().put("tabId", "2")).code())
        assertEquals("TAB_NOT_FOUND", tools.call("browser_focus_tab", JSONObject().put("tabId", "99")).code())
        assertEquals("POLICY_DENIED", tools.call("browser_close_tab", JSONObject().put("tabId", "2")).code())
        assertTrue(host.tabs.containsKey(2))
    }

    @Test
    fun `a private tab is never the agent's, even marked held`() = runBlocking {
        host.add(page(1, private = true))
        // Held from before it was known to be private: still refused.
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        assertEquals(0, tools.call("browser_list_tabs").result().getJSONArray("tabs").length())
        assertEquals("POLICY_DENIED", tools.call("browser_focus_tab", JSONObject().put("tabId", "1")).code())
    }

    @Test
    fun `a tab created by the chat is held by it, after its address is approved`() = runBlocking {
        val tools = toolset()
        val out = tools.call("browser_create_tab", JSONObject().put("url", "https://example.com/"))
        val action = asked.single().action as AgentAction.Navigate
        assertEquals("https://example.com/", action.url)
        assertTrue(action.newTab)
        val id = out.result().getString("activeTabId").toLong()
        assertEquals("chat", AgentTabs.holder(id))
        assertEquals("1", tools.call("browser_get_tab").result().getJSONObject("tab").getInt("navigationId").toString())
    }

    @Test
    fun `a declined address opens nothing`() = runBlocking {
        answer = { ApprovalOutcome.Declined }
        val out = toolset().call("browser_create_tab", JSONObject().put("url", "https://example.com/"))
        assertEquals("USER_DECLINED", out.code())
        assertTrue(out.output.contains("\"action\":\"stop\""))
        assertTrue(host.tabs.isEmpty())
    }

    @Test
    fun `internal pages can't be read, but can be navigated away from`() = runBlocking {
        host.add(page(1, url = "", internal = true))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        assertEquals("POLICY_DENIED", tools.call("browser_snapshot").code())
        assertTrue(host.scripts.isEmpty())
        val nav = tools.call("browser_navigate", JSONObject().put("url", "https://example.com/"))
        assertFalse(nav.isError)
        assertEquals(listOf(1L to "https://example.com/"), host.navigations)
    }

    private suspend fun snapshotRef(tools: BrowserToolset): String {
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        val snap = tools.call("browser_snapshot").result()
        assertTrue(snap.getString("untrusted").contains("untrusted"))
        assertFalse(snap.getJSONObject("page").has("fingerprints"))
        return snap.getJSONObject("page").getJSONArray("elements").getJSONObject(0).getString("ref")
    }

    @Test
    fun `a click is asked about with the control, and runs on screen once approved`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        val out = tools.call("browser_click", JSONObject().put("ref", ref).put("intent", "Run\nthe search"))
        assertFalse(out.output, out.isError)
        val a = asked.single()
        assertEquals(1L, a.tabId)
        assertEquals("example.com", a.site)
        assertEquals(3, a.document)
        assertEquals(PageTarget("button", "button", "Search"), (a.action as AgentAction.Click).target)
        assertEquals("Run the search", a.intent)
        assertEquals(listOf(1L), host.fronted)
        assertEquals(listOf(100f to 200f), host.taps)
    }

    @Test
    fun `a declined click is never made, and the tab isn't brought forward`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        answer = { ApprovalOutcome.Declined }
        assertEquals("USER_DECLINED", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        assertTrue(host.fronted.isEmpty())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `a withdrawn approval isn't a decline, and nothing runs`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        answer = { ApprovalOutcome.Withdrawn }
        val out = tools.call("browser_click", JSONObject().put("ref", ref))
        assertEquals("APPROVAL_REQUIRED", out.code())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `the page navigating after the approval refuses the click`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        host.onFront = { host.add(page(1, document = 4)) }
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `the control changing after the approval refuses the click`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        host.onFront = { host.control = JSONObject(host.control.toString()).put("target", "https://evil.example/") }
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `the user taking the tab back while it's asked refuses the click`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        answer = { AgentTabs.release(1); ApprovalOutcome.Approved }
        assertEquals("POLICY_DENIED", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        assertTrue(host.fronted.isEmpty())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `a tab that can't be put on screen runs nothing`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        host.canFront = false
        assertEquals("CAPABILITY_UNAVAILABLE", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `a reference from another tab, an old document or nowhere is refused before asking`() = runBlocking {
        host.add(page(1))
        host.add(page(2))
        AgentTabs.hold(1, "chat", private = false)
        AgentTabs.hold(2, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        tools.call("browser_focus_tab", JSONObject().put("tabId", "2"))
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        host.add(page(1, document = 5))
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_click", JSONObject().put("ref", "e00000000_0")).code())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `typing shows the exact text on the card`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        host.control = JSONObject().put("role", "textbox").put("tag", "input").put("name", "Search the library")
        val tools = toolset()
        val ref = snapshotRef(tools)
        val out = tools.call("browser_type", JSONObject().put("ref", ref).put("text", "swarm").put("replace", false))
        assertFalse(out.output, out.isError)
        val action = asked.single().action as AgentAction.Type
        assertEquals("swarm", action.text)
        assertFalse(action.replace)
    }

    @Test
    fun `arguments are checked as desktop checks them`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        for (url in listOf("javascript:alert(1)", "file:///sdcard/x", "https://user:pw@example.com/", "example.com", "data:text/html,x")) {
            assertEquals(url, "INVALID_ARGUMENT", tools.call("browser_navigate", JSONObject().put("url", url)).code())
        }
        assertEquals("INVALID_ARGUMENT", tools.call("browser_press", JSONObject().put("ref", "x").put("key", "F5")).code())
        assertEquals(
            "INVALID_ARGUMENT",
            tools.call("browser_select", JSONObject().put("ref", "x").put("value", "a").put("values", JSONArray())).code(),
        )
        assertEquals("INVALID_ARGUMENT", tools.call("browser_scroll", JSONObject().put("ref", "x").put("direction", "down").put("pages", 5)).code())
        assertEquals("INVALID_ARGUMENT", tools.call("browser_snapshot", JSONObject().put("elementOffset", 10)).code())
        assertEquals("INVALID_ARGUMENT", tools.call("browser_wait", JSONObject().put("condition", "sleep")).code())
        assertEquals("INVALID_ARGUMENT", tools.call("browser_handle_dialog", JSONObject().put("dialogRef", "d").put("accept", true)).code())
        assertTrue(asked.isEmpty())
        assertTrue(host.navigations.isEmpty())
    }

    @Test
    fun `no browser means every tool says so`() = runBlocking {
        val tools = BrowserToolset("chat", browser = { null }, ask = { ApprovalOutcome.Approved }, pageScript = { "PAGE" })
        assertEquals("CAPABILITY_UNAVAILABLE", tools.call("browser_list_tabs").code())
        assertEquals("CAPABILITY_UNAVAILABLE", tools.call("browser_create_tab", JSONObject().put("url", "https://example.com/")).code())
    }

    @Test
    fun `a page's dialog is answered only as approved, and only while it's the same one`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        assertTrue(tools.call("browser_get_dialog").result().isNull("dialog"))
        host.dialog = AgentDialog("prompt", "Your name?\u202E", "x", "https://example.com/")
        val got = tools.call("browser_get_dialog").result()
        assertTrue(got.getString("untrusted").contains("untrusted"))
        val ref = got.getJSONObject("dialog").getString("dialogRef")
        // Declined: the dialog is left as it is.
        answer = { ApprovalOutcome.Declined }
        assertEquals("USER_DECLINED", tools.call("browser_handle_dialog", JSONObject().put("dialogRef", ref).put("accept", true).put("promptText", "Ann")).code())
        assertTrue(host.answers.isEmpty())
        answer = { ApprovalOutcome.Approved }
        assertFalse(tools.call("browser_handle_dialog", JSONObject().put("dialogRef", ref).put("accept", true).put("promptText", "Ann")).isError)
        val shown = asked.last().action as AgentAction.Dialog
        assertEquals("Your name?\uFFFD", shown.message)
        assertEquals("Ann", shown.promptText)
        assertEquals(listOf(true to "Ann"), host.answers)
        // A ref for a dialog that has since been replaced by another is stale.
        host.dialog = AgentDialog("confirm", "Delete?", null, "https://example.com/")
        val second = tools.call("browser_get_dialog").result().getJSONObject("dialog").getString("dialogRef")
        host.dialog = AgentDialog("confirm", "Delete everything?", null, "https://example.com/")
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_handle_dialog", JSONObject().put("dialogRef", second).put("accept", true)).code())
        assertEquals(1, host.answers.size)
    }

    @Test
    fun `a tab still between pages says to wait rather than to stop`() = runBlocking {
        host.add(page(1, url = "", internal = true).copy(loading = true))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        val out = tools.call("browser_snapshot")
        assertEquals("TAB_BUSY", out.code())
        assertTrue(out.output.contains("browser_wait"))
    }

    @Test
    fun `typed text and addresses are capped at what the card shows`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        val long = "https://example.com/" + "a".repeat(BrowserArgs.MAX_URL_CHARS)
        assertEquals("INVALID_ARGUMENT", tools.call("browser_navigate", JSONObject().put("url", long)).code())
        val ref = snapshotRef(tools)
        assertEquals(
            "INVALID_ARGUMENT",
            tools.call("browser_type", JSONObject().put("ref", ref).put("text", "x".repeat(BrowserArgs.MAX_TYPED_CHARS + 1))).code(),
        )
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `only website pages are readable`() {
        for (url in listOf("example.com/", "https://example.com/a", "http://10.0.2.2:8701/", "name.eth/", "bzz://abc/")) {
            assertTrue(url, agentReadable(url))
        }
        for (url in listOf("", "about:blank", "ABOUT:srcdoc", "data:text/html,<b>x</b>", "javascript:alert(1)", "file:///sdcard/a", "content://x/y", "chrome://version")) {
            assertFalse(url, agentReadable(url))
        }
    }

    private suspend fun typeRef(tag: String): Pair<BrowserToolset, String> {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        host.control = JSONObject().put("role", "textbox").put("tag", tag).put("name", "Message")
        val tools = toolset()
        return tools to snapshotRef(tools)
    }

    @Test
    fun `a lone line break is never typed, since it would press Enter`() = runBlocking {
        val (tools, ref) = typeRef("textarea")
        for (text in listOf("\n", "\r\n", "\r")) {
            for (replace in listOf(true, false)) {
                val out = tools.call("browser_type", JSONObject().put("ref", ref).put("text", text).put("replace", replace))
                assertEquals("INVALID_ARGUMENT", out.code())
                assertTrue(out.output.contains("browser_press"))
            }
        }
        assertTrue(asked.isEmpty())
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun `line breaks go only into a multi-line field, and the card marks them`() = runBlocking {
        val (tools, ref) = typeRef("input")
        assertEquals("INVALID_ARGUMENT", tools.call("browser_type", JSONObject().put("ref", ref).put("text", "hi\nthere")).code())
        assertTrue(asked.isEmpty())
        host.control = JSONObject().put("role", "textbox").put("tag", "textarea").put("name", "Message")
        val ref2 = snapshotRef(tools)
        assertFalse(tools.call("browser_type", JSONObject().put("ref", ref2).put("text", "hi\nthere")).isError)
        assertEquals(listOf("hi\nthere"), host.commits)
        val typed = (asked.single().action as AgentAction.Type).text
        assertEquals(ApprovalText.Typed.Shown("hi ↵ there"), ApprovalText.typed(typed, 2_000))
    }

    @Test
    fun `taking the tab back between typing's steps stops the rest`() = runBlocking {
        val (tools, ref) = typeRef("input")
        // No input connection the first time; the tab is taken back before the tap fallback.
        host.commitAnswer = { AgentTabs.release(1); false }
        val out = tools.call("browser_type", JSONObject().put("ref", ref).put("text", "secret"))
        assertEquals("POLICY_DENIED", out.code())
        assertTrue(out.output.contains("part"))
        assertEquals(1, host.commits.size)
        assertTrue(host.taps.isEmpty())
        assertTrue(host.cancelledTaps.isEmpty())
    }

    @Test
    fun `taking the tab back mid-tap cancels the touch`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        host.duringTap = { AgentTabs.release(1) }
        assertEquals("POLICY_DENIED", tools.call("browser_click", JSONObject().put("ref", ref)).code())
        assertTrue(host.taps.isEmpty())
        assertEquals(listOf(100f to 200f), host.cancelledTaps)
    }

    @Test
    fun `taking the tab back before a key press sends no key`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        host.control = JSONObject().put("role", "textbox").put("tag", "input").put("name", "Search")
        val tools = toolset()
        val ref = snapshotRef(tools)
        // Taken back while the focus script runs, before the key goes in.
        host.onScript = { op -> if (op == "focus") AgentTabs.release(1) }
        assertEquals("POLICY_DENIED", tools.call("browser_press", JSONObject().put("ref", ref).put("key", "Enter")).code())
        assertTrue(host.keys.isEmpty())
    }

    @Test
    fun `a scroll is the page's own scroll, never a touch`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        val snap = tools.call("browser_snapshot").result()
        val token = snap.getJSONObject("page").getJSONArray("elements").getJSONObject(0).getString("ref").substringBefore('_')
        val out = tools.call("browser_scroll", JSONObject().put("ref", token + "_viewport").put("direction", "down").put("pages", 1))
        assertFalse(out.output, out.isError)
        assertEquals("moved", out.result().getString("result"))
        assertTrue(host.scripts.any { it.startsWith("PAGE(\"scroll\"") && it.contains("\"pages\":1") })
        assertTrue(host.taps.isEmpty())
        assertTrue(asked.single().action is AgentAction.Scroll)
    }

    @Test
    fun `a navigation runs only on the document and site the card named`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        // The tab moves to another site while the card is up.
        answer = { host.add(page(1, url = "other.example/", document = 4)); ApprovalOutcome.Approved }
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_navigate", JSONObject().put("url", "https://example.com/next")).code())
        assertTrue(host.navigations.isEmpty())
    }

    @Test
    fun `a dialog shown again after the approval isn't answered by it`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        host.dialog = AgentDialog("confirm", "Delete?", null, "https://embedded.example/frame", serial = 1)
        val ref = tools.call("browser_get_dialog").result().getJSONObject("dialog").getString("dialogRef")
        // The page reloads under the card and asks the very same thing again.
        answer = { host.dialog = host.dialog!!.copy(serial = 2); ApprovalOutcome.Approved }
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_handle_dialog", JSONObject().put("dialogRef", ref).put("accept", true)).code())
        assertTrue(host.answers.isEmpty())
        // The card names the frame that opened the dialog, the approval the tab's site.
        val a = asked.single()
        assertEquals("embedded.example", (a.action as AgentAction.Dialog).from)
        assertEquals("example.com", a.site)
    }
}
