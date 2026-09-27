package baby.freedom.mobile.browser

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import baby.freedom.mobile.R
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The context a private tab's (#86) WebView is built on: the Activity,
 * except that every window Chromium opens from it comes up `FLAG_SECURE`.
 *
 * A page's `<select>` list, date/time/colour pickers and similar are
 * Chromium's own dialogs — separate windows, outside the Activity window
 * [PrivateScreenGuard] secures, so without this their contents (the
 * select's options, say) would show in screenshots, casts and, on
 * API 30–32, the Recents snapshot. There's no WebView API to reach
 * those dialogs, but Chromium builds them on the WebView's context, and
 * every context wrapped around it (Chromium's resource wrapper, the
 * dialog's theme wrapper) clones its `LayoutInflater` from this one,
 * factory included. So the factory sees the dialog's views as they're
 * inflated into its not-yet-shown window, and secures that window the
 * moment it's attached — before its first frame is drawn.
 *
 * Windows only: views inflated into a window that's already on screen
 * (the Activity's own, whose flag [PrivateScreenGuard] owns) are left
 * alone. One instance per Activity while any private tab holds it, so
 * Chromium (which keys its window bookkeeping by context) sees the same
 * one for every private tab.
 */
internal class PrivateWindowContext private constructor(base: Context) : ContextWrapper(base) {

    private val inflater: LayoutInflater by lazy {
        LayoutInflater.from(base).cloneInContext(this).also { clone ->
            // A clone carries its original's factory and refuses a second
            // one; the Activity (a plain ComponentActivity) sets none.
            if (clone.factory == null) clone.factory2 = SecureWindowFactory
        }
    }

    override fun getSystemService(name: String): Any? =
        if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)

    companion object {
        // Each wrapper's base is its key (the Activity), so the cache
        // holds wrappers weakly: a strong value would keep its own key
        // reachable and pin every Activity that ever opened a private tab.
        private val instances = WeakValueCache<Context, PrivateWindowContext>(::PrivateWindowContext)

        fun of(context: Context): PrivateWindowContext =
            context as? PrivateWindowContext ?: instances[context]
    }
}

/**
 * A per-key cache that holds neither side strongly: the key weakly (as
 * [WeakHashMap] does) and the value through a [WeakReference], so a
 * value that references its own key — a [ContextWrapper] around the
 * Activity it's keyed by — doesn't keep that key, and so itself, alive.
 * A value lives as long as something outside the cache holds it; once
 * nothing does, the next lookup builds a fresh one.
 */
internal class WeakValueCache<K : Any, V : Any>(private val create: (K) -> V) {
    private val map = WeakHashMap<K, WeakReference<V>>()

    operator fun get(key: K): V =
        map[key]?.get() ?: create(key).also { map[key] = WeakReference(it) }
}

/**
 * Creates nothing itself (returns null, so the inflater builds each view
 * as usual); it only uses each inflation's parent to find the window
 * root being filled, and secures that window once it's attached.
 */
private object SecureWindowFactory : LayoutInflater.Factory2 {
    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val root = parent?.rootView ?: return null
        if (root.isAttachedToWindow || root.getTag(R.id.private_window_secured) != null) return null
        root.setTag(R.id.private_window_secured, true)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Runs in the window's first traversal, before it's laid out
                // and drawn: the relayout that follows carries the flag.
                val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
                if (lp.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_SECURE
                val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
                runCatching { wm.updateViewLayout(v, lp) }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        return null
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = null
}
