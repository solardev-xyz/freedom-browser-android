package baby.freedom.swarm

import android.os.Parcelable
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize

/**
 * A line kept in a node's state as a string resource and its arguments,
 * read in the app language each time it's shown ([text]), so it follows
 * a change of the per-app language instead of staying in the one it was
 * made in (#313 R1-M5). Parcelable, so it can cross to the node processes
 * and back: a resource id means the same in every process of one APK.
 * [raw] is words that aren't a resource (Arti's own, an exception's).
 */
@Parcelize
data class HeldText(
    @StringRes val id: Int = 0,
    val args: List<HeldText> = emptyList(),
    val raw: String? = null,
) : Parcelable {
    val text: String
        get() = raw ?: SwarmStrings.get(id, *args.map { it.text }.toTypedArray())

    override fun toString(): String = text

    companion object {
        /** [id] with [args]: a [HeldText] argument resolves with it, anything else as text. */
        fun res(@StringRes id: Int, vararg args: Any?): HeldText =
            HeldText(id, args.map { it as? HeldText ?: raw(it.toString()) })

        /** Words that aren't a resource: shown as they are. */
        fun raw(text: String): HeldText = HeldText(raw = text)
    }
}
