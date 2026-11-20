package baby.freedom.mobile.agent

import androidx.compose.runtime.mutableStateMapOf

/**
 * Which tabs Freedom Agent holds (#575), and for which chat: desktop's
 * Agent custody (`freedom-agent-service.js` `agentTabs`). A chat's tools
 * touch only the tabs it holds: the ones it opened, and the windows a
 * page in one of them opened (desktop's `openCustodyPopup`). A tab stays
 * held after the turn ends, so the next message can go on in it, until
 * the user takes it back ([release]: the strip on the tab, or closing
 * the tab) or deletes the chat ([releaseChat]).
 *
 * Never a private tab: [hold] refuses one, so nothing the agent does
 * can reach a private session. Compose-observable, so the tab's strip
 * and the switcher's mark follow it. Main thread for writes.
 */
internal object AgentTabs {
    private val holders = mutableStateMapOf<Long, String>()

    /** Held popup -> the held tab whose page opened it ([popupOpened]). */
    private val openers = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** The chat holding [tabId], or null. */
    fun holder(tabId: Long): String? = holders[tabId]

    fun isHeld(tabId: Long): Boolean = tabId in holders

    /** The held tab whose page opened held popup [tabId], or null. */
    fun opener(tabId: Long): Long? = openers[tabId]

    /** The tabs [sessionId] holds, in the order they were taken. */
    fun heldBy(sessionId: String): List<Long> = holders.entries.filter { it.value == sessionId }.map { it.key }

    /** [sessionId] takes [tabId]; false for a private tab or one another chat holds. */
    fun hold(tabId: Long, sessionId: String, private: Boolean): Boolean {
        if (private) return false
        val current = holders[tabId]
        if (current != null && current != sessionId) return false
        holders[tabId] = sessionId
        return true
    }

    /**
     * A window a page in [openerId] opened: held by the same chat if the
     * opener is held (custody only, as on desktop; the chat may act in it
     * like any tab it holds). A private opener's popup is private too and
     * is never held.
     */
    fun popupOpened(openerId: Long, popupId: Long, private: Boolean) {
        val chat = holders[openerId] ?: return
        if (hold(popupId, chat, private)) openers[popupId] = openerId
    }

    /** The user takes [tabId] back: the agent can't touch it any more. */
    fun release(tabId: Long) {
        holders.remove(tabId)
        openers.remove(tabId)
        AgentApprovals.withdrawTab(tabId)
    }

    /** [tabId] closed. */
    fun closed(tabId: Long) = release(tabId)

    /** [sessionId] was deleted: every tab it held goes back to the user. */
    fun releaseChat(sessionId: String) {
        for (id in heldBy(sessionId)) release(id)
    }

    /** For tests. */
    internal fun clear() {
        holders.clear()
        openers.clear()
    }
}
