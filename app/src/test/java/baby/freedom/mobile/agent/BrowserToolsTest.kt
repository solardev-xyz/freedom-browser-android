package baby.freedom.mobile.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        /** What the page's file input holds, by its own word (#600). */
        var attachedFiles = 1

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
                "files" -> JSONObject().put("ok", true).put("count", attachedFiles).toString()
                "scroll", "scrollState" -> JSONObject().put("ok", true)
                    .put("scroll", JSONObject().put("x", 0).put("y", if (op == "scroll") 0 else 800)).toString()
                else -> JSONObject().put("ok", true).toString()
            }
        }

        private fun tokenOf(script: String) = JSONObject(script.substringAfter("\",").removeSuffix(")")).getString("token")

        val frontedFor = ArrayList<String?>()
        override suspend fun bringToFront(id: Long, chat: String?): Boolean {
            fronted += id
            frontedFor += chat
            onFront()
            return canFront
        }

        /** Whether the tab is already on screen, for a step the mode lets run without a card. */
        var shown: () -> Boolean = { true }
        override fun onScreen(id: Long) = shown()

        /** Run between a tap's touch down and its lift. */
        var duringTap: () -> Unit = {}
        val cancelledTaps = ArrayList<Pair<Float, Float>>()

        /** The tab goes off screen with the finger down: the host cancels the touch. */
        var offScreenMidTap = false

        override suspend fun tap(id: Long, x: Float, y: Float, proceed: () -> Boolean): AgentTap {
            duringTap()
            if (!proceed() || offScreenMidTap) {
                cancelledTaps += x to y
                return AgentTap.Cancelled
            }
            taps += x to y
            return AgentTap.Tapped
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

    private val files = FakeFiles(java.nio.file.Files.createTempDirectory("agent-files").toFile())

    private fun toolset(
        session: String = "chat",
        approvals: ChatApprovals = ChatApprovals.AskEveryTime,
        filesPort: AgentFilesPort = files,
    ) = BrowserToolset(
        session,
        browser = { host },
        ask = { a -> asked += a; answer(a) },
        pageScript = { "PAGE" },
        approvals = approvals,
        files = { filesPort },
    )

    /** A chat in [mode] whose classifier answers [check], recording what it was given. */
    private class Approvals(var mode0: ApprovalMode, var check: InteractionClassification) : ChatApprovals {
        val inputs = ArrayList<InteractionInput>()
        override val mode get() = mode0
        override suspend fun classifyInteraction(input: InteractionInput): InteractionClassification {
            inputs += input
            return check
        }
    }

    private val ordinary = InteractionClassification(InteractionKind.ORDINARY, 0.95, "Open the result.", emptyList())

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
        AgentDownloads.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AgentTabs.clear()
        AgentDownloads.clear()
    }

    @Test
    fun `the tool names and schemas are desktop's`() {
        val names = toolset().tools.map { it.spec.name }
        assertEquals(
            listOf(
                "browser_get_dialog", "browser_handle_dialog", "browser_list_tabs", "browser_create_tab", "browser_get_tab",
                "browser_focus_tab", "browser_close_tab", "browser_snapshot", "browser_navigate", "browser_click",
                "browser_type", "browser_select", "browser_scroll", "browser_press", "browser_wait", "browser_stop_loading",
                "browser_upload", "browser_download", "browser_list_downloads",
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
        assertEquals(listOf<String?>("chat"), host.frontedFor)
        assertEquals(listOf(100f to 200f), host.taps)
        // The tab was already on screen (the fake host closed no chat page):
        // the tools themselves never mark the chat to reopen.
        assertNull(AgentBrowser.takeChatToReopen())
    }

    @Test
    fun `a tap cancelled because the tab went off screen says it may have reached the page`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        val ref = snapshotRef(tools)
        host.offScreenMidTap = true
        val out = tools.call("browser_click", JSONObject().put("ref", ref))
        assertEquals("CAPABILITY_UNAVAILABLE", out.code())
        assertTrue(out.output, out.output.contains("may have acted"))
        assertFalse(out.output, out.output.contains("Nothing was clicked"))
        assertEquals(listOf(100f to 200f), host.cancelledTaps)
        assertTrue(host.taps.isEmpty())
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
        assertEquals(ApprovalText.Typed.Shown("hi↵\nthere"), ApprovalText.typed(typed, 2_000))
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

    @Test
    fun `allow website interactions clicks without a card, still bound to the page`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val approvals = Approvals(ApprovalMode.AllowWebsiteInteractions, InteractionIntentClassifier.uncertain())
        val tools = toolset(approvals = approvals)
        val ref = snapshotRef(tools)
        assertFalse(tools.call("browser_click", JSONObject().put("ref", ref)).isError)
        assertTrue(asked.isEmpty())
        assertTrue(approvals.inputs.isEmpty())
        assertEquals(listOf(100f to 200f), host.taps)
        // The page changing before it runs refuses it, card or no card.
        val again = snapshotRef(tools)
        host.onFront = { host.add(page(1, document = 4)) }
        assertEquals("STALE_ELEMENT_REFERENCE", tools.call("browser_click", JSONObject().put("ref", again)).code())
        assertEquals(1, host.taps.size)
        // A navigation runs without a card too.
        host.onFront = {}
        assertFalse(tools.call("browser_navigate", JSONObject().put("url", "https://example.org/")).isError)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a relaxed mode never takes the screen without the user's tap`() = runBlocking {
        for (mode in listOf(ApprovalMode.AllowWebsiteInteractions, ApprovalMode.SensitiveActions)) {
            host.tabs.clear()
            AgentTabs.clear()
            asked.clear()
            host.fronted.clear()
            host.taps.clear()
            host.add(page(1))
            AgentTabs.hold(1, "chat", private = false)
            val tools = toolset(approvals = Approvals(mode, ordinary))
            // Another tab, a panel or the chat is on screen: switching asks, saying why.
            host.shown = { false }
            answer = { ApprovalOutcome.Declined }
            assertEquals("$mode", "USER_DECLINED", tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools))).code())
            assertEquals(ApprovalCheck.NeedsScreen, asked.single().check)
            assertTrue(host.fronted.isEmpty())
            assertTrue(host.taps.isEmpty())
            // The user's OK switches to it, as any approved card does.
            asked.clear()
            answer = { ApprovalOutcome.Approved }
            assertFalse(tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools))).isError)
            assertEquals(1, asked.size)
            assertEquals(listOf(1L), host.fronted)
            assertEquals(1, host.taps.size)
            // On screen when decided, gone before it runs: refused, nothing switched or tapped.
            asked.clear()
            host.fronted.clear()
            var reads = 0
            host.shown = { reads++ == 0 }
            assertEquals("CAPABILITY_UNAVAILABLE", tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools))).code())
            assertTrue(asked.isEmpty())
            assertTrue(host.fronted.isEmpty())
            assertEquals(1, host.taps.size)
            // Already on screen: it runs without a card.
            host.shown = { true }
            assertFalse(tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools))).isError)
            assertTrue(asked.isEmpty())
            assertEquals(2, host.taps.size)
        }
        host.shown = { true }
    }

    @Test
    fun `downloads, file choosers and dialog answers ask in every mode`() = runBlocking {
        for (mode in ApprovalMode.entries) {
            for (effect in listOf("file_download", "file_upload")) {
                host.tabs.clear()
                AgentTabs.clear()
                asked.clear()
                host.add(page(1))
                AgentTabs.hold(1, "chat", private = false)
                host.control = JSONObject().put("role", "link").put("tag", "a").put("name", "Get it").put("effect", effect)
                    .put("target", "https://example.com/file.zip")
                val approvals = Approvals(mode, ordinary)
                val tools = toolset(approvals = approvals)
                // Their own tools (#600), which ask whatever the mode: declined here, so nothing runs.
                answer = { ApprovalOutcome.Declined }
                val tool = if (effect == "file_download") "browser_download" else "browser_upload"
                assertEquals("$mode $effect", "USER_DECLINED", tools.call(tool, JSONObject().put("ref", snapshotRef(tools))).code())
                assertEquals("$mode $effect", 1, asked.size)
                assertTrue(approvals.inputs.isEmpty())
                // A key on the control asks as well (the page's own download or chooser waits for the user).
                answer = { ApprovalOutcome.Approved }
                assertFalse(tools.call("browser_press", JSONObject().put("ref", snapshotRef(tools)).put("key", "Enter")).isError)
                assertEquals("$mode $effect press", 2, asked.size)
                assertTrue(approvals.inputs.isEmpty())
            }
            asked.clear()
            host.dialog = AgentDialog("confirm", "Leave?", null, "https://example.com/")
            val approvals = Approvals(mode, ordinary)
            val tools = toolset(approvals = approvals)
            tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
            val dialogRef = tools.call("browser_get_dialog").result().getJSONObject("dialog").getString("dialogRef")
            assertFalse(tools.call("browser_handle_dialog", JSONObject().put("dialogRef", dialogRef).put("accept", true)).isError)
            assertEquals("$mode dialog", 1, asked.size)
            assertTrue(approvals.inputs.isEmpty())
        }
    }

    @Test
    fun `sensitive actions runs what the check calls ordinary and asks, with its reason, otherwise`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val approvals = Approvals(ApprovalMode.SensitiveActions, ordinary)
        val tools = toolset(approvals = approvals)
        val ref = snapshotRef(tools)
        assertFalse(tools.call("browser_click", JSONObject().put("ref", ref).put("intent", "Open details")).isError)
        assertTrue(asked.isEmpty())
        val input = approvals.inputs.single()
        assertEquals("browser_click", input.operation)
        assertEquals("Open details", input.intent)
        assertEquals("example.com", input.origin)
        assertEquals("Search", input.label)
        assertEquals(1, host.taps.size)

        approvals.check = InteractionClassification(InteractionKind.CONSEQUENTIAL, 0.97, "Posts the comment.", emptyList())
        val ref2 = snapshotRef(tools)
        assertFalse(tools.call("browser_click", JSONObject().put("ref", ref2)).isError)
        assertEquals(ApprovalCheck.Judged("Posts the comment."), asked.single().check)
        assertEquals(2, host.taps.size)

        // A form submission asks without the model.
        asked.clear()
        approvals.inputs.clear()
        approvals.check = ordinary
        host.control = JSONObject(host.control.toString()).put("effect", "form_submission").put("target", "https://example.com/post")
        val ref3 = snapshotRef(tools)
        answer = { ApprovalOutcome.Declined }
        assertEquals("USER_DECLINED", tools.call("browser_click", JSONObject().put("ref", ref3)).code())
        assertEquals(ApprovalCheck.SendsForm, asked.single().check)
        assertTrue(approvals.inputs.isEmpty())
        assertEquals(2, host.taps.size)

        // A check that gave no answer says so, not as the model's word.
        asked.clear()
        answer = { ApprovalOutcome.Approved }
        host.control = JSONObject().put("role", "button").put("tag", "button").put("name", "Search")
        approvals.check = InteractionIntentClassifier.uncertain(ClassifierReason.TIMEOUT)
        val ref4 = snapshotRef(tools)
        assertFalse(tools.call("browser_click", JSONObject().put("ref", ref4)).isError)
        assertEquals(ApprovalCheck.NoAnswer, asked.single().check)
    }

    @Test
    fun `the action kinds follow what the page's markup says, which can only make it ask`() {
        val t = PageTarget("button", "button", "Go")
        assertEquals(ActionKind.PageInteraction, BrowserToolset.kindOf(AgentAction.Click(t)))
        assertEquals(ActionKind.FormSubmission, BrowserToolset.kindOf(AgentAction.Press(t.copy(effect = "form_submission"), "Enter")))
        assertEquals(ActionKind.Download, BrowserToolset.kindOf(AgentAction.Click(t.copy(effect = "file_download"))))
        assertEquals(ActionKind.Upload, BrowserToolset.kindOf(AgentAction.Click(t.copy(effect = "file_upload"))))
        assertEquals(ActionKind.Navigate, BrowserToolset.kindOf(AgentAction.Navigate("https://a.example/", newTab = true)))
        assertEquals(ActionKind.Dialog, BrowserToolset.kindOf(AgentAction.Dialog("alert", "hi", true, null)))
        assertEquals(ActionKind.PageInteraction, BrowserToolset.kindOf(AgentAction.Scroll(null, "down", 1.0)))
        // A password or one-time-code field: typing or a key into it is a credential; a tap isn't.
        val code = PageTarget("textbox", "input", "Code", sensitive = true)
        assertEquals(ActionKind.Credential, BrowserToolset.kindOf(AgentAction.Type(code, "123456", replace = true)))
        assertEquals(ActionKind.Credential, BrowserToolset.kindOf(AgentAction.Press(code, "5")))
        assertEquals(ActionKind.PageInteraction, BrowserToolset.kindOf(AgentAction.Click(code)))
        // The page's markup for a form still wins: it asks either way.
        assertEquals(ActionKind.FormSubmission, BrowserToolset.kindOf(AgentAction.Press(code.copy(effect = "form_submission"), "Enter")))
    }

    @Test
    fun `typing into a password or one-time-code field asks in sensitive actions without the model`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        host.control = JSONObject().put("role", "textbox").put("tag", "input").put("name", "Code").put("sensitive", true)
        val approvals = Approvals(ApprovalMode.SensitiveActions, ordinary)
        val tools = toolset(approvals = approvals)
        val ref = snapshotRef(tools)
        answer = { ApprovalOutcome.Declined }
        assertEquals("USER_DECLINED", tools.call("browser_type", JSONObject().put("ref", ref).put("text", "123456")).code())
        val card = asked.single()
        assertEquals(ApprovalCheck.Credential, card.check)
        assertTrue((card.action as AgentAction.Type).target.sensitive)
        assertTrue(approvals.inputs.isEmpty())
    }

    private fun downloadLink(href: String = "https://example.com/files/report.pdf") {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        host.control = JSONObject().put("role", "link").put("tag", "a").put("name", "Report").put("effect", "file_download")
            .put("target", href)
    }

    private fun fileInput() {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        host.control = JSONObject().put("role", "button").put("tag", "input").put("name", "Photo").put("effect", "file_upload")
    }

    @Test
    fun `a plain click on a download link or a file input is refused before asking`() = runBlocking {
        downloadLink()
        val tools = toolset(approvals = Approvals(ApprovalMode.AllowWebsiteInteractions, ordinary))
        val out = tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("CAPABILITY_UNAVAILABLE", out.code())
        assertTrue(out.output.contains("browser_download"))
        fileInput()
        val up = tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools)))
        assertTrue(up.output.contains("browser_upload"))
        assertTrue(asked.isEmpty())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `an approved download saves the link it was approved for, and its receipt names no path`() = runBlocking {
        downloadLink()
        files.rows[42] = files.entry(42, "report.pdf", "completed")
        host.duringTap = {
            // What DownloadManager.start asks as the page's download comes: the link itself.
            val claim = AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!
            assertTrue(claim.direct)
            claim.started(42)
        }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertFalse(out.output, out.isError)
        val action = asked.single().action as AgentAction.Download
        assertEquals("https://example.com/files/report.pdf", action.target.target)
        assertEquals(listOf(100f to 200f), host.taps)
        val artifact = out.result().getJSONObject("artifact")
        assertEquals("report.pdf", artifact.getString("filename"))
        assertEquals("completed", artifact.getString("state"))
        assertEquals("https://example.com", artifact.getString("sourceOrigin"))
        assertEquals("downloads", artifact.getString("location"))
        assertTrue(artifact.getBoolean("available"))
        assertFalse(out.output.contains("content://"))
        // Listed for this chat only.
        val listed = tools.call("browser_list_downloads").result().getJSONArray("artifacts")
        assertEquals(artifact.getString("artifactId"), listed.getJSONObject(0).getString("artifactId"))
        assertEquals(0, toolset(session = "other").call("browser_list_downloads").result().getJSONArray("artifacts").length())
        // Removed from the downloads list: still listed, no longer available.
        files.rows.remove(42)
        val gone = tools.call("browser_list_downloads").result().getJSONArray("artifacts").getJSONObject(0)
        assertEquals("removed", gone.getString("state"))
        assertFalse(gone.getBoolean("available"))
    }

    @Test
    fun `the download card shows the link's address in full, as matched`() = runBlocking {
        // Padding past where other cards cut, with the part that picks the file at the end.
        val href = "https://drive.example/uc?export=download&x=" + "p".repeat(1_500) + "&id=the-file"
        downloadLink(href)
        files.rows[42] = files.entry(42, "report.pdf", "completed")
        host.duringTap = { AgentDownloads.claim(1, href)!!.started(42) }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertFalse(out.output, out.isError)
        assertEquals(href, (asked.single().action as AgentAction.Download).target.target)

        // The page swaps the end of the address while the card is up: refused, nothing tapped.
        AgentTabs.clear()
        host.tabs.clear()
        asked.clear()
        host.taps.clear()
        downloadLink(href)
        answer = { host.control.put("target", href.replace("the-file", "other-file")); ApprovalOutcome.Approved }
        host.duringTap = { error("not tapped") }
        val swapped = toolset()
        assertEquals("STALE_ELEMENT_REFERENCE", swapped.call("browser_download", JSONObject().put("ref", snapshotRef(swapped))).code())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `a link whose address the card can't show as it is is refused before asking`() = runBlocking {
        downloadLink("https://example.com/files/report.pdf\u202E&id=other")
        val tools = toolset()
        assertEquals("ELEMENT_NOT_INTERACTABLE", tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools))).code())
        assertTrue(asked.isEmpty())
        assertTrue(host.taps.isEmpty())
    }

    @Test
    fun `asking where to save, turned on while the card is up, still counts`() = runBlocking {
        downloadLink()
        answer = { files.askWhere = true; ApprovalOutcome.Approved }
        var direct: Boolean? = null
        host.duringTap = {
            val claim = AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!
            direct = claim.direct
            claim.declined()
        }
        val tools = toolset()
        assertEquals("DOWNLOAD_CANCELLED_BY_USER", tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools))).code())
        assertEquals(false, direct)
    }

    @Test
    fun `an approved download Freedom couldn't list is reported, not waited on`() = runBlocking {
        downloadLink()
        host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.failed() }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("CAPABILITY_UNAVAILABLE", out.code())
        assertTrue(files.records.of("chat").isEmpty())
    }

    @Test
    fun `stopping the turn after the claim but before the row stops the download once it starts`() = runBlocking {
        downloadLink()
        files.rows[42] = files.entry(42, "report.pdf", "running")
        var claim: AgentDownloadClaim? = null
        host.duringTap = { claim = AgentDownloads.claim(1, "https://example.com/files/report.pdf")!! }
        val tools = toolset()
        val ref = snapshotRef(tools)
        val job = launch { tools.call("browser_download", JSONObject().put("ref", ref)) }
        while (claim == null) kotlinx.coroutines.yield()
        job.cancel()
        job.join()
        // Its row comes after the Stop: stopped as it starts, never recorded.
        claim!!.started(42)
        assertEquals(listOf(42L), files.cancelled)
        assertTrue(files.records.of("chat").isEmpty())
    }

    @Test
    fun `a download other than the approved link is held for the user and reported`() = runBlocking {
        downloadLink()
        host.duringTap = { assertNull(AgentDownloads.claim(1, "https://elsewhere.example/report.pdf")) }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("APPROVAL_REQUIRED", out.code())
        assertTrue(out.output.contains("unapproved download"))
        // Said once, not again with the next step.
        assertFalse(AgentDownloads.takeHeld(1))
    }

    @Test
    fun `a download the approved link redirects to, even on its own origin, is held for the user`() = runBlocking {
        downloadLink()
        host.duringTap = {
            // A page on a shared host swaps in another tenant's file on the link's origin.
            assertNull(AgentDownloads.claim(1, "https://example.com/other-user/payload.apk"))
        }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("APPROVAL_REQUIRED", out.code())
        assertTrue(out.output.contains("Do not retry"))
        assertTrue(files.records.of("chat").isEmpty())
    }

    @Test
    fun `a download no approval covers is noted with the click that caused it`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        host.duringTap = { assertNull(AgentDownloads.claim(1, "https://example.com/surprise.exe")) }
        val tools = toolset()
        val out = tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools)))
        assertFalse(out.isError)
        assertTrue(out.result().getString("heldDownload").contains("browser_download"))
    }

    @Test
    fun `asking where to save each file leaves the approved download to the user's own prompt`() = runBlocking {
        downloadLink()
        files.askWhere = true
        host.duringTap = {
            val claim = AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!
            assertFalse(claim.direct)
            claim.declined()
        }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("DOWNLOAD_CANCELLED_BY_USER", out.code())
        assertTrue(out.output.contains("\"action\":\"stop\""))
    }

    @Test
    fun `a download the user cancels or that pauses is never reported as saved`() = runBlocking {
        for ((status, code) in listOf("cancelled" to "DOWNLOAD_CANCELLED_BY_USER", "paused" to "CAPABILITY_UNAVAILABLE", "failed" to "CAPABILITY_UNAVAILABLE")) {
            host.tabs.clear()
            AgentTabs.clear()
            downloadLink()
            files.rows[7] = files.entry(7, "report.pdf", status)
            host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.started(7) }
            val tools = toolset()
            assertEquals(status, code, tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools))).code())
        }
    }

    @Test
    fun `an upload is the user's pick in the picker, and the agent learns only its name, size and type`() = runBlocking {
        fileInput()
        host.duringTap = {
            val intent = AgentUploads.take(1)!!
            intent.opened.complete(true)
            intent.result.complete(UploadIntent.Result.Picked(emptyList(), object : android.content.ContentResolver(null) {}))
        }
        val tools = toolset(approvals = Approvals(ApprovalMode.AllowWebsiteInteractions, ordinary))
        val out = tools.call("browser_upload", JSONObject().put("ref", snapshotRef(tools)))
        assertFalse(out.output, out.isError)
        assertTrue(asked.single().action is AgentAction.Upload)
        val upload = out.result().getJSONObject("upload")
        assertEquals("photo.jpg", upload.getString("filename"))
        assertEquals(1234, upload.getLong("bytes"))
        assertEquals("image/jpeg", upload.getString("mimeType"))
        assertEquals("attached", upload.getString("state"))
        assertNull(AgentUploads.take(1))

        // Cancelled in the picker: the user's no, not retried.
        host.duringTap = {
            val intent = AgentUploads.take(1)!!
            intent.opened.complete(true)
            intent.result.complete(UploadIntent.Result.Cancelled)
        }
        val cancelled = tools.call("browser_upload", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("FILE_UPLOAD_CANCELLED_BY_USER", cancelled.code())
        assertTrue(cancelled.output.contains("\"action\":\"stop\""))

        // Closed by the app (not the user): not their cancel.
        host.duringTap = {
            val intent = AgentUploads.take(1)!!
            intent.opened.complete(true)
            intent.result.complete(UploadIntent.Result.Withdrawn)
        }
        assertEquals("CAPABILITY_UNAVAILABLE", tools.call("browser_upload", JSONObject().put("ref", snapshotRef(tools))).code())

        // Chosen, but nothing chosen may be attached (Freedom's own files): not the user's cancel.
        host.duringTap = {
            val intent = AgentUploads.take(1)!!
            intent.opened.complete(true)
            intent.result.complete(UploadIntent.Result.Refused)
        }
        val refused = tools.call("browser_upload", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("CAPABILITY_UNAVAILABLE", refused.code())
        assertFalse(refused.output.contains("\"action\":\"stop\""))
        assertTrue(refused.output.contains("different file"))

        // A page that says its input holds nothing afterwards isn't reported as attached.
        host.attachedFiles = 0
        host.duringTap = {
            val intent = AgentUploads.take(1)!!
            intent.opened.complete(true)
            intent.result.complete(UploadIntent.Result.Picked(emptyList(), object : android.content.ContentResolver(null) {}))
        }
        assertEquals("INTERNAL_ERROR", tools.call("browser_upload", JSONObject().put("ref", snapshotRef(tools))).code())
    }

    @Test
    fun `upload and download refuse a control of the wrong kind before asking`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        val tools = toolset()
        assertEquals("ELEMENT_NOT_INTERACTABLE", tools.call("browser_upload", JSONObject().put("ref", snapshotRef(tools))).code())
        assertEquals("ELEMENT_NOT_INTERACTABLE", tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools))).code())
        // A link address as long as the page script hands over may have been cut: it can't be matched.
        downloadLink()
        val ref = snapshotRef(tools)
        host.onScript = { if (it == "describe") host.control.put("target", "https://example.com/" + "a".repeat(4096)) }
        assertEquals("ELEMENT_NOT_INTERACTABLE", tools.call("browser_download", JSONObject().put("ref", ref)).code())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a pick made after the user took the tab back is not reported to the agent`() = runBlocking {
        fileInput()
        host.duringTap = {
            val intent = AgentUploads.take(1)!!
            intent.opened.complete(true)
            AgentTabs.release(1)
            intent.result.complete(UploadIntent.Result.Picked(emptyList(), object : android.content.ContentResolver(null) {}))
        }
        val tools = toolset()
        val out = tools.call("browser_upload", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("POLICY_DENIED", out.code())
        assertFalse(out.output.contains("photo.jpg"))
    }

    @Test
    fun `a second download the approved tap starts is reported with the saved one`() = runBlocking {
        downloadLink()
        files.rows[42] = files.entry(42, "report.pdf", "completed")
        host.duringTap = {
            AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.started(42)
            // The link's handler then sends the page to another file: the user's to answer.
            assertNull(AgentDownloads.claim(1, "https://example.com/other.zip"))
        }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertFalse(out.output, out.isError)
        assertEquals("report.pdf", out.result().getJSONObject("artifact").getString("filename"))
        assertTrue(out.result().getString("heldDownload").contains("download prompt"))
        // Said once, not again with the next step.
        assertFalse(AgentDownloads.takeHeld(1))
    }

    @Test
    fun `a second download is reported with a refused approved one too`() = runBlocking {
        downloadLink()
        files.askWhere = true
        host.duringTap = {
            val claim = AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!
            assertNull(AgentDownloads.claim(1, "https://example.com/other.zip"))
            claim.declined()
        }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("DOWNLOAD_CANCELLED_BY_USER", out.code())
        assertTrue(out.output, out.output.contains("no approval covered"))
        assertFalse(AgentDownloads.takeHeld(1))
    }

    @Test
    fun `an approved download that can't be recorded is stopped`() = runBlocking {
        downloadLink()
        files.rows[42] = files.entry(42, "report.pdf", "running")
        files.onRow = { throw IllegalStateException("database is locked") }
        host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.started(42) }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertTrue(out.isError)
        assertEquals(listOf(42L), files.cancelled)
        assertTrue(files.records.of("chat").isEmpty())
    }

    @Test
    fun `an approved download that can't be followed once recorded is stopped`() = runBlocking {
        downloadLink()
        files.rows[42] = files.entry(42, "report.pdf", "running")
        val failing = object : AgentFilesPort by files {
            override suspend fun settled(rowId: Long): baby.freedom.mobile.data.DownloadEntry? = throw IllegalStateException("database is locked")
        }
        host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.started(42) }
        val tools = toolset(filesPort = failing)
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertTrue(out.isError)
        assertEquals(listOf(42L), files.cancelled)
    }

    @Test
    fun `a download the approved link starts in a new window is reported as waiting, not as none`() = runBlocking {
        downloadLink()
        host.add(page(2))
        host.duringTap = {
            // The link's handler window.open()s the file: the popup is held with its opener.
            AgentTabs.popupOpened(1, 2, private = false)
            assertNull(AgentDownloads.claim(2, "https://example.com/files/report.pdf"))
        }
        val tools = toolset()
        val began = System.nanoTime()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("APPROVAL_REQUIRED", out.code())
        assertTrue(out.output, out.output.contains("new window"))
        // Answered as it happened, not after the start wait ran out.
        assertTrue((System.nanoTime() - began) / 1_000_000 < 5_000)
        assertTrue(files.records.of("chat").isEmpty())
        assertFalse(AgentDownloads.takeHeld(1))
    }

    @Test
    fun `the approved link's download after the start wait ran out is reported with the next step, not asked again`() = runBlocking {
        downloadLink()
        val tools = toolset()
        // The page does nothing within the start wait.
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("WAIT_TIMEOUT", out.code())
        assertTrue(out.output, out.output.contains("Do not call browser_download on this link again"))
        host.control = JSONObject().put("role", "button").put("tag", "button").put("name", "Next")
        val next = snapshotRef(tools)
        // Then it starts the approved link after all: held for the user, not saved.
        assertNull(AgentDownloads.claim(1, "https://example.com/files/report.pdf"))
        assertTrue(files.records.of("chat").isEmpty())
        // The next step's start doesn't clear it unreported: said with that step, once.
        val click = tools.call("browser_click", JSONObject().put("ref", next))
        assertFalse(click.isError)
        assertTrue(click.result().getString("lateDownload").contains("Do not call browser_download"))
        assertFalse(click.result().has("heldDownload"))
        assertFalse(tools.call("browser_snapshot").result().has("lateDownload"))
    }

    @Test
    fun `a snapshot reports the approved link's late download`() = runBlocking {
        downloadLink()
        val earlier = DownloadIntent("chat", 1, "https://example.com/files/report.pdf", direct = true)
        assertTrue(AgentDownloads.arm(earlier))
        assertTrue(AgentDownloads.lapse(earlier))
        assertNull(AgentDownloads.claim(1, "https://example.com/files/report.pdf"))
        val tools = toolset()
        tools.call("browser_focus_tab", JSONObject().put("tabId", "1"))
        assertTrue(tools.call("browser_snapshot").result().getString("lateDownload").contains("download prompt"))
        assertFalse(tools.call("browser_snapshot").result().has("lateDownload"))
    }

    @Test
    fun `browser_download on a tab whose approved download came late says so instead of asking again`() = runBlocking {
        downloadLink()
        val tools = toolset()
        val ref = snapshotRef(tools)
        val earlier = DownloadIntent("chat", 1, "https://example.com/files/report.pdf", direct = true)
        assertTrue(AgentDownloads.arm(earlier))
        assertTrue(AgentDownloads.lapse(earlier))
        assertNull(AgentDownloads.claim(1, "https://example.com/files/report.pdf"))
        val out = tools.call("browser_download", JSONObject().put("ref", ref))
        assertEquals("APPROVAL_REQUIRED", out.code())
        assertTrue(out.output, out.output.contains("waits for the user in Freedom's download prompt"))
        assertTrue(asked.isEmpty())
        // Said once: a new call asks as usual.
        host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.declined() }
        val again = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("DOWNLOAD_CANCELLED_BY_USER", again.code())
        assertEquals(1, asked.size)
    }

    @Test
    fun `the earlier approved download coming while the retry's card is up is said, and nothing is tapped`() = runBlocking {
        downloadLink()
        val earlier = DownloadIntent("chat", 1, "https://example.com/files/report.pdf", direct = true)
        assertTrue(AgentDownloads.arm(earlier))
        assertTrue(AgentDownloads.lapse(earlier))
        var tapped = false
        host.duringTap = { tapped = true }
        answer = {
            // The page starts the first call's download while the user reads this card.
            assertNull(AgentDownloads.claim(1, "https://example.com/files/report.pdf"))
            ApprovalOutcome.Approved
        }
        val tools = toolset()
        val out = tools.call("browser_download", JSONObject().put("ref", snapshotRef(tools)))
        assertEquals("APPROVAL_REQUIRED", out.code())
        assertTrue(out.output, out.output.contains("waits for the user in Freedom's download prompt"))
        assertEquals(1, asked.size)
        assertFalse(tapped)
        assertTrue(files.records.of("chat").isEmpty())
    }

    @Test
    fun `a download held before a step isn't reported with it`() = runBlocking {
        host.add(page(1))
        AgentTabs.hold(1, "chat", private = false)
        // The page's own download (or the user's), before the agent's click.
        assertNull(AgentDownloads.claim(1, "https://example.com/earlier.zip"))
        val tools = toolset()
        val out = tools.call("browser_click", JSONObject().put("ref", snapshotRef(tools)))
        assertFalse(out.isError)
        assertFalse(out.result().has("heldDownload"))
    }

    @Test
    fun `stopping the turn stops the download it started`() = runBlocking {
        downloadLink()
        files.rows[42] = files.entry(42, "report.pdf", "running")
        files.running = kotlinx.coroutines.CompletableDeferred()
        host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.started(42) }
        val tools = toolset()
        val ref = snapshotRef(tools)
        val job = launch { tools.call("browser_download", JSONObject().put("ref", ref)) }
        while (files.records.of("chat").isEmpty()) kotlinx.coroutines.yield()
        job.cancel()
        job.join()
        assertEquals(listOf(42L), files.cancelled)
    }

    @Test
    fun `stopping the turn while the started download is being recorded stops it too`() = runBlocking {
        downloadLink()
        files.rows[42] = files.entry(42, "report.pdf", "running")
        files.running = kotlinx.coroutines.CompletableDeferred()
        val inRow = kotlinx.coroutines.CompletableDeferred<Unit>()
        files.rowGate = kotlinx.coroutines.CompletableDeferred()
        files.onRow = { inRow.complete(Unit) }
        host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.started(42) }
        val tools = toolset()
        val ref = snapshotRef(tools)
        val job = launch { tools.call("browser_download", JSONObject().put("ref", ref)) }
        // Stopped while the started download's row is still being read, before it's recorded.
        inRow.await()
        job.cancel()
        job.join()
        assertEquals(listOf(42L), files.cancelled)
    }

    @Test
    fun `stopping the turn leaves a download the user accepted in their own prompt running`() = runBlocking {
        downloadLink()
        files.askWhere = true
        files.rows[42] = files.entry(42, "report.pdf", "running")
        files.running = kotlinx.coroutines.CompletableDeferred()
        host.duringTap = { AgentDownloads.claim(1, "https://example.com/files/report.pdf")!!.started(42) }
        val tools = toolset()
        val ref = snapshotRef(tools)
        val job = launch { tools.call("browser_download", JSONObject().put("ref", ref)) }
        while (files.records.of("chat").isEmpty()) kotlinx.coroutines.yield()
        job.cancel()
        job.join()
        assertTrue(files.cancelled.isEmpty())
    }
}
