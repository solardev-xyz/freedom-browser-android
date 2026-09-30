package baby.freedom.mobile.l10n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.ServiceLoader

/**
 * Where user-visible text comes from outside Compose (#280): logic that
 * builds a line for the UI (an error message, a status line, a
 * notification) without a `Context` at hand. Compose code uses
 * `stringResource` / `pluralStringResource` instead, which also redraws
 * when the language changes.
 *
 * Every string lives in `res/values/strings*.xml`; a translation is a
 * `res/values-<lang>/` copy of those files and nothing else.
 *
 * [FreedomApplication] points it at the app's resources in every
 * process before anything else runs. Off-device (JVM unit tests) there
 * is no `Context`: the test source set registers a [StringSource] that
 * reads the same XML files, found through [ServiceLoader], so a test
 * still sees the English text.
 */
object Strings {
    @Volatile
    private var source: StringSource? = null

    /** Resolve from [context]'s application resources (their locale follows the app language). */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        source = ResourcesStringSource(app)
    }

    /** The text of [id]. */
    fun get(@StringRes id: Int): String = source().string(id)

    /** The text of [id] with [args] filled in (`%1$s`, `%1$d`, …, locale-formatted). */
    fun get(@StringRes id: Int, vararg args: Any?): String = source().string(id, *args)

    /**
     * The form of [id] for [count] (`one`, `other`, … by the language's
     * plural rules), with [args] filled in. Pass the count again in
     * [args] when the text shows it.
     */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
        source().plural(id, count, *args)

    private fun source(): StringSource =
        source ?: synchronized(this) {
            source ?: ServiceLoader.load(StringSource::class.java, Strings::class.java.classLoader)
                .firstOrNull()
                ?.also { source = it }
            ?: error("Strings used before FreedomApplication.onCreate")
        }
}

/** Resolves string and plural resources by id. */
interface StringSource {
    fun string(@StringRes id: Int, vararg args: Any?): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String
}

private class ResourcesStringSource(private val context: Context) : StringSource {
    // `context.resources` each time, not kept: the per-app language
    // (Android 13+) updates the application's resources in place.
    override fun string(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.resources.getQuantityString(id, count)
        else context.resources.getQuantityString(id, count, *args)
}
