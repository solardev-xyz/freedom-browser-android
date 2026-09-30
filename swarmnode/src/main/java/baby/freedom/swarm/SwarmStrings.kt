package baby.freedom.swarm

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.ServiceLoader

/**
 * Where this module's user-visible text comes from (#280): the node
 * page's recovery and error lines, Tor and Radicle status text, the
 * errors a spend reports back. It can't use the app's `Strings`, so it
 * mirrors it: every string lives in `res/values/strings_swarmnode.xml`
 * (names `swarmnode_…`), merged into the app's resources, and a
 * translation is a `res/values-<lang>/` copy of that file.
 *
 * The app's `FreedomApplication` calls [init] in every process (the
 * nodes run in their own). Off-device (JVM unit tests) there is no
 * `Context`: a test source set registers a [SwarmStringSource] in
 * `META-INF/services` that reads the same XML, found through
 * [ServiceLoader], so a test still sees the English text.
 */
object SwarmStrings {
    @Volatile
    private var source: SwarmStringSource? = null

    /** Resolve from [context]'s application resources (their locale follows the app language). */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        source = ResourcesSource(app)
    }

    /** The text of [id], with [args] filled in (`%1$s`, `%1$d`, …, locale-formatted). */
    fun get(@StringRes id: Int, vararg args: Any?): String = source().string(id, *args)

    /** The form of [id] for [count], with [args] filled in; pass the count again in [args] when the text shows it. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = source().plural(id, count, *args)

    private fun source(): SwarmStringSource =
        source ?: synchronized(this) {
            source ?: ServiceLoader.load(SwarmStringSource::class.java, SwarmStrings::class.java.classLoader)
                .firstOrNull()
                ?.also { source = it }
            ?: error("SwarmStrings used before SwarmStrings.init")
        }
}

/** Resolves this module's string and plural resources by id. */
interface SwarmStringSource {
    fun string(@StringRes id: Int, vararg args: Any?): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String
}

private class ResourcesSource(private val context: Context) : SwarmStringSource {
    // `context.resources` each time, not kept: the per-app language
    // (Android 13+) updates the application's resources in place.
    override fun string(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.resources.getQuantityString(id, count)
        else context.resources.getQuantityString(id, count, *args)
}
