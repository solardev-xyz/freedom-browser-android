package baby.freedom.swarm

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import java.util.Locale
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

    /**
     * Resolve from [context]'s application resources (their locale follows
     * the app language). [plural] resolves a plural: the form in the rules
     * of the language the text is in, which the app knows and this module
     * doesn't, and the arguments formatted in the context's locale like any
     * other string's (the app's `TextLocale.plural`, #313 R1-F1, R2-M1,
     * R3-M1). One function, so the app's and this module's counts agree.
     */
    fun init(context: Context, plural: PluralResolver = PluralResolver(::devicePlural)) {
        val app = context.applicationContext ?: context
        source = ResourcesSource(app, plural)
    }

    @Suppress("DevicePluralRules") // the fallback when no app says what language the text is in
    private fun devicePlural(context: Context, id: Int, count: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) context.resources.getQuantityString(id, count)
        else context.resources.getQuantityString(id, count, *args)

    /** The text of [id], with [args] filled in (`%1$s`, `%1$d`, …, locale-formatted). */
    fun get(@StringRes id: Int, vararg args: Any?): String = source().string(id, *args)

    /** The form of [id] for [count], with [args] filled in; pass the count again in [args] when the text shows it. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = source().plural(id, count, *args)

    /**
     * [get] in English, for text that leaves the app (a page's
     * `window.radicle` reply) and so must not reveal the app language.
     */
    fun english(@StringRes id: Int, vararg args: Any?): String = source().english(id, *args)

    /** [plural] in English; see [english]. */
    fun englishPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = source().englishPlural(id, count, *args)

    /**
     * Tests only: resolve through [test] instead (null: back to the usual
     * source), to check what a translated build shows and sends.
     */
    @VisibleForTesting
    fun useForTest(test: SwarmStringSource?) {
        source = test
    }

    private fun source(): SwarmStringSource =
        source ?: synchronized(this) {
            source ?: ServiceLoader.load(SwarmStringSource::class.java, SwarmStrings::class.java.classLoader)
                .firstOrNull()
                ?.also { source = it }
            ?: error("SwarmStrings used before SwarmStrings.init")
        }
}

/** Resolves plural [id] for [count] against [context], with [args] filled in. */
fun interface PluralResolver {
    fun plural(context: Context, @PluralsRes id: Int, count: Int, args: Array<out Any?>): String
}

/** Resolves this module's string and plural resources by id. */
interface SwarmStringSource {
    fun string(@StringRes id: Int, vararg args: Any?): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String

    /** [string] in English. The default suits a source that only has English. */
    fun english(@StringRes id: Int, vararg args: Any?): String = string(id, *args)

    /** [plural] in English. The default suits a source that only has English. */
    fun englishPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = plural(id, count, *args)
}

@Suppress("DevicePluralRules") // englishPlural(): en-US resources, en-US rules
private class ResourcesSource(
    private val context: Context,
    private val pluralResolver: PluralResolver,
) : SwarmStringSource {
    // `context.resources` each time, not kept: the per-app language
    // (Android 13+) updates the application's resources in place.
    override fun string(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any?): String =
        pluralResolver.plural(context, id, count, args)

    // `values/` is en-US (the app's res/resources.properties); formatted with en-US's digits too.
    // Built each time: cheap next to a seed line, and it follows a configuration change.
    private fun englishResources() = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.US) },
    ).resources

    override fun english(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) englishResources().getString(id) else englishResources().getString(id, *args)

    override fun englishPlural(id: Int, count: Int, vararg args: Any?): String =
        if (args.isEmpty()) englishResources().getQuantityString(id, count)
        else englishResources().getQuantityString(id, count, *args)
}
