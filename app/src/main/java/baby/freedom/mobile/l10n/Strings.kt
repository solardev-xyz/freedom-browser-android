package baby.freedom.mobile.l10n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import java.util.Locale
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

    /**
     * The text of [id] in English, whatever the app language: for words
     * that leave the phone — an EIP-1193 or OpenLV error a page or a peer
     * reads — which must not tell every site the user's language.
     */
    fun english(@StringRes id: Int, vararg args: Any?): String = source().english(id, *args)

    /**
     * [id] in the app language and in English at once ([Said]): for a
     * message the wallet shows the user *and* hands a page or a peer.
     * An argument that is itself a [Said] goes in in the matching language.
     */
    fun said(@StringRes id: Int, vararg args: Any?): Said = Said(
        get(id, *args.map { if (it is Said) it.text else it }.toTypedArray()),
        english(id, *args.map { if (it is Said) it.english else it }.toTypedArray()),
    )

    /**
     * Tests only: resolve through [test] instead (null: back to the usual
     * source), to check what a translated build shows and sends.
     */
    @VisibleForTesting
    internal fun useForTest(test: StringSource?) {
        source = test
    }

    private fun source(): StringSource =
        source ?: synchronized(this) {
            source ?: ServiceLoader.load(StringSource::class.java, Strings::class.java.classLoader)
                .firstOrNull()
                ?.also { source = it }
            ?: error("Strings used before FreedomApplication.onCreate")
        }
}

/**
 * One message in the app language ([text], for the user) and in English
 * ([english], for a page or a peer: the developer-facing side of #280).
 * A message that didn't come from a resource (a node's own words) is the
 * same in both ([of]).
 */
data class Said(val text: String, val english: String) {
    override fun toString(): String = text

    companion object {
        /** Words that are the same in every language: a node's, a library's. */
        fun of(text: String): Said = Said(text, text)
    }
}

/** Resolves string and plural resources by id. */
interface StringSource {
    fun string(@StringRes id: Int, vararg args: Any?): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String

    /** [string] in English. The default suits a source that only has English. */
    fun english(@StringRes id: Int, vararg args: Any?): String = string(id, *args)
}

private class ResourcesStringSource(private val context: Context) : StringSource {
    // `context.resources` each time, not kept: the per-app language
    // (Android 13+) updates the application's resources in place.
    override fun string(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.resources.getQuantityString(id, count)
        else context.resources.getQuantityString(id, count, *args)

    // `values/` is en-US (res/resources.properties); formatted with en-US's digits too.
    private val englishResources: Resources by lazy {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.US) }
        context.createConfigurationContext(config).resources
    }

    override fun english(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) englishResources.getString(id) else englishResources.getString(id, *args)
}

/**
 * Words kept as a resource and its arguments, resolved each time they're
 * read ([text]): for a line held in state or a cache, so it follows a
 * change of the per-app language instead of staying in the one it was
 * made in (#280). An argument that is itself a [Text] resolves with it.
 * [raw] is words that come from elsewhere (an exception's, a server's).
 */
class Text private constructor(
    @StringRes private val id: Int,
    private val args: List<Any?>,
    private val raw: String?,
) {
    val text: String
        get() = raw ?: if (args.isEmpty()) Strings.get(id) else Strings.get(id, *args.map { if (it is Text) it.text else it }.toTypedArray())

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is Text && other.id == id && other.args == args && other.raw == raw
    override fun hashCode(): Int = (id * 31 + args.hashCode()) * 31 + raw.hashCode()

    companion object {
        /** [id] with [args], read in the app language whenever it's shown. */
        fun res(@StringRes id: Int, vararg args: Any?): Text = Text(id, args.toList(), null)

        /** Words that aren't a resource: shown as they are. */
        fun raw(text: String): Text = Text(0, emptyList(), text)
    }
}
