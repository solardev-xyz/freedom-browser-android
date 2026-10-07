package baby.freedom.mobile.browser

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import baby.freedom.mobile.data.SitePermissionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Site permissions are double-keyed by (requesting origin, top-level
 * site) (#363): a grant a site was given as a site never answers that
 * site framed by another one.
 */
class SitePermissionScopeTest {

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(data.value).also { data.value = it }
    }

    private val meet = "https://meet.example"
    private val evil = "https://evil.example"
    private val news = "https://news.example"
    private val camera = listOf<SiteCapability>(SitePermission.CAMERA)

    /** The broker's own lookup: [permissionScopeFor], then [rememberedDecisions], then [planFor]. */
    private suspend fun plan(
        store: SitePermissionStore,
        session: PermissionSession,
        requesting: String,
        page: String?,
        permissions: List<SiteCapability> = camera,
    ): PermissionPlan? {
        val scope = permissionScopeFor(requesting, page) ?: return null
        return planFor(scope, permissions, rememberedDecisions(store, scope), session)
    }

    @Test
    fun `a frame's scope is the pair, a page's own is plain`() {
        assertEquals(PermissionScope(meet, evil), permissionScopeFor("https://meet.example/", evil))
        assertTrue(PermissionScope(meet, evil).embedded)
        assertEquals(PermissionScope(meet), permissionScopeFor("https://MEET.example:443/room", meet))
        assertFalse(PermissionScope(meet).embedded)
        // A frame in a page with no site of its own has nothing to ask in the name of.
        assertNull(permissionScopeFor("https://meet.example/", null))
        assertNull(permissionScopeFor("data:text/html,hi", evil))
    }

    @Test
    fun `R3-F1 - a blob document asks in the name of the site that minted it`() {
        val top = documentPermissionOrigin("blob:http://localhost:8730/6f1c2d3e-uuid")
        assertEquals("http://localhost:8730", top)
        assertEquals("https://meet.example", documentPermissionOrigin("BLOB:https://Meet.example:443/x"))
        // …so its own getUserMedia is that site asking for itself, prompted as usual.
        assertEquals(
            PermissionScope("http://localhost:8730"),
            permissionScopeFor("http://localhost:8730/", top),
        )
        // An opaque origin's blob, and non-http(s) documents, still have none.
        assertNull(documentPermissionOrigin("blob:null/6f1c2d3e"))
        assertNull(documentPermissionOrigin("data:text/html,hi"))
        assertNull(documentPermissionOrigin("about:blank"))
        assertEquals(meet, documentPermissionOrigin("https://meet.example/room"))
    }

    @Test
    fun `issue 363 - a remembered top-level grant does not answer the same site framed by another`() = runBlocking {
        val store = SitePermissionStore(MemoryStore())
        val session = PermissionSession()
        // 1. The user visits meet.example and allows the camera, every visit.
        assertTrue(store.set(meet, SitePermission.CAMERA.key, PermissionDecision.ALLOW.stored))
        assertEquals(PermissionPlan.Grant, plan(store, session, "https://meet.example/", meet))
        // 2-3. evil.example frames meet.example with allow="camera": asked, not granted.
        assertEquals(PermissionPlan.Ask(camera), plan(store, session, "https://meet.example/", evil))
    }

    @Test
    fun `a this-session top-level grant does not answer the framed site either`() = runBlocking {
        val store = SitePermissionStore(MemoryStore())
        val session = PermissionSession()
        session.record(PermissionScope(meet), SitePermission.CAMERA, PermissionDecision.ALLOW, remembered = false)
        assertEquals(PermissionPlan.Grant, plan(store, session, meet, meet))
        assertEquals(PermissionPlan.Ask(camera), plan(store, session, meet, evil))
    }

    @Test
    fun `a frame's decision is remembered for its pair alone`() = runBlocking {
        val store = SitePermissionStore(MemoryStore())
        val session = PermissionSession()
        // Allowed meet.example inside news.example, remembered.
        assertTrue(store.set(meet, SitePermission.CAMERA.key, PermissionDecision.ALLOW.stored, top = news))
        assertEquals(PermissionPlan.Grant, plan(store, session, meet, news))
        // Not meet.example as a site, nor meet.example inside evil.example.
        assertEquals(PermissionPlan.Ask(camera), plan(store, session, meet, meet))
        assertEquals(PermissionPlan.Ask(camera), plan(store, session, meet, evil))
        // Nor news.example's own page.
        assertEquals(PermissionPlan.Ask(camera), plan(store, session, news, news))
        // A block for the pair blocks the pair only.
        session.record(PermissionScope(meet, evil), SitePermission.CAMERA, PermissionDecision.DENY, remembered = false)
        assertEquals(PermissionPlan.Deny, plan(store, session, meet, evil))
        assertEquals(PermissionPlan.Grant, plan(store, session, meet, news))
    }

    @Test
    fun `dismissals embargo the pair, not the framed site everywhere`() = runBlocking {
        val store = SitePermissionStore(MemoryStore())
        val session = PermissionSession()
        repeat(PermissionSession.DISMISS_EMBARGO_THRESHOLD) { session.dismiss(PermissionScope(meet, evil), SitePermission.CAMERA) }
        assertEquals(PermissionPlan.Deny, plan(store, session, meet, evil))
        assertEquals(PermissionPlan.Ask(camera), plan(store, session, meet, meet))
        val embargo = session.entries().single()
        assertEquals(meet, embargo.origin)
        assertEquals(evil, embargo.top)
        assertTrue(embargo.embargoed)
    }

    @Test
    fun `decisions stored before 363 migrate as the pair (origin, origin)`() = runBlocking {
        // The pre-#363 format, `<origin>|<permission>`, with no rewrite.
        val old = mutablePreferencesOf(
            stringPreferencesKey("perm:$meet|camera") to "allow",
            stringPreferencesKey("perm:$meet|geolocation") to "deny",
            stringPreferencesKey("perm:$news|popups") to "allow",
        )
        val store = SitePermissionStore(MemoryStore(old))
        assertEquals(
            listOf(
                SitePermissionStore.Record(meet, "camera", "allow", top = meet),
                SitePermissionStore.Record(meet, "geolocation", "deny", top = meet),
                SitePermissionStore.Record(news, "popups", "allow", top = news),
            ),
            store.all.first(),
        )
        assertEquals(mapOf("camera" to "allow", "geolocation" to "deny"), store.decisionsFor(meet, meet))
        // They still apply to the site itself…
        val session = PermissionSession()
        assertEquals(PermissionPlan.Grant, plan(store, session, meet, meet))
        assertEquals(PermissionPlan.Deny, plan(store, session, meet, meet, listOf(SitePermission.LOCATION)))
        // …and never to it framed by another site.
        assertEquals(emptyMap<String, String>(), store.decisionsFor(meet, evil))
        assertEquals(PermissionPlan.Ask(camera), plan(store, session, meet, evil))
        // Removing the site's own decision removes the old key itself.
        assertTrue(store.remove(meet, "camera", top = meet))
        assertEquals(mapOf("geolocation" to "deny"), store.decisionsFor(meet))
    }

    @Test
    fun `the store keeps a site's own key plain and puts a frame's top-level site last`() = runBlocking {
        val backing = MemoryStore()
        val store = SitePermissionStore(backing)
        assertTrue(store.set(meet, "camera", "allow"))
        assertTrue(store.set(meet, "camera", "deny", top = evil))
        assertEquals(
            setOf("perm:$meet|camera", "perm:$meet|camera|$evil"),
            backing.data.value.asMap().keys.map { it.name }.toSet(),
        )
        assertEquals(mapOf("camera" to "allow"), store.decisionsFor(meet, meet))
        assertEquals(mapOf("camera" to "deny"), store.decisionsFor(meet, evil))
        // The pair is removed on its own.
        assertTrue(store.remove(meet, "camera", top = evil))
        assertEquals(mapOf("camera" to "allow"), store.decisionsFor(meet, meet))
        assertEquals(emptyMap<String, String>(), store.decisionsFor(meet, evil))
    }

    @Test
    fun `malformed keys are ignored`() = runBlocking {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("perm:$meet|camera|$evil|extra") to "allow",
            stringPreferencesKey("perm:$meet||$evil") to "allow",
            stringPreferencesKey("perm:$meet|camera|") to "allow",
            stringPreferencesKey("perm:$meet") to "allow",
        )
        assertEquals(emptyList<SitePermissionStore.Record>(), SitePermissionStore(MemoryStore(prefs)).all.first())
    }

    @Test
    fun `Settings lists a frame's decision under the site it was made on`() {
        fun e(origin: String, p: SiteCapability, top: String = origin, remembered: Boolean = true) =
            SitePermissionEntry(origin, p, PermissionDecision.ALLOW, remembered, top = top)
        val listed = sitePermissionListOrder(
            listOf(
                e(meet, SitePermission.CAMERA, top = news),
                e(meet, SitePermission.CAMERA),
                e(news, SitePermission.LOCATION, remembered = false),
                e(news, SitePermission.CAMERA),
                e("https://a.example", SitePermission.MICROPHONE, top = news),
                e(evil, SitePermission.MIDI),
            ),
        )
        assertEquals(
            listOf(
                evil to evil,
                meet to meet,
                news to news,
                news to news,
                "https://a.example" to news,
                meet to news,
            ),
            listed.map { it.origin to it.top },
        )
        // news.example's own: camera before location (declared order).
        assertEquals(SitePermission.CAMERA, listed[2].permission)
        assertEquals("Embedded: meet.example", embeddedSiteLine(listed[5]))
        assertNull(embeddedSiteLine(listed[1]))
        assertEquals(
            "Remove Camera permission for meet.example embedded in news.example",
            sitePermissionRemoveLabel(listed[5]),
        )
        assertEquals("Remove Camera permission for meet.example", sitePermissionRemoveLabel(listed[1]))
    }

    @Test
    fun `the prompt names the site the user is on and the frame it asks through`() {
        assertEquals("wants to use your camera", permissionPromptSentence(PermissionScope(meet), camera))
        assertNull(embeddedPermissionHint(PermissionScope(meet)))
        val framed = PermissionScope(meet, evil)
        assertEquals("wants to use your camera via meet.example", permissionPromptSentence(framed, camera))
        assertEquals(
            "meet.example is embedded in this page. Your answer counts only on evil.example, not when you visit meet.example itself.",
            embeddedPermissionHint(framed),
        )
        // The title is the top-level site.
        assertEquals(evil, PermissionPrompt(framed, camera).scope.top)
    }

    @Test
    fun `removing a site's own grant leaves what it holds framed elsewhere, and the reverse`() {
        val own = PermissionScope(meet)
        val framed = PermissionScope(meet, evil)
        val doc = SitePermissionBroker.DocumentPermissions(doc = 1)
            .granting(framed, listOf(SitePermission.CAMERA))
        val ownEntry = SitePermissionEntry(meet, SitePermission.CAMERA, PermissionDecision.ALLOW, remembered = true)
        val framedEntry = ownEntry.copy(top = evil)
        // The frame's document isn't touched by removing meet.example's own grant…
        assertEquals(doc, doc.revoking(ownEntry))
        assertFalse(doc.inUse(ownEntry, setOf(SitePermission.CAMERA)))
        // …but is by removing the pair's.
        assertEquals(mapOf(framed to setOf(SitePermission.CAMERA)), doc.revoking(framedEntry).revokedHeld)
        assertTrue(doc.inUse(framedEntry, setOf(SitePermission.CAMERA)))
        // And allowing meet.example again as a site doesn't clear the pair's note.
        val removed = doc.revoking(framedEntry)
        assertEquals(removed, removed.allowedAgain(own, listOf(SitePermission.CAMERA)))
    }
}
