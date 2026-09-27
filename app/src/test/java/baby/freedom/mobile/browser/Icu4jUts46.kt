package baby.freedom.mobile.browser

import com.ibm.icu.text.IDNA

/** [WhatwgHost.Uts46] on icu4j — the same calls the app makes on `android.icu`. */
object Icu4jUts46 : WhatwgHost.Uts46 {
    private val idna = IDNA.getUTS46Instance(WhatwgHost.Uts46.OPTIONS)

    override fun toAscii(domain: String): String? {
        val out = StringBuilder()
        val info = IDNA.Info()
        idna.nameToASCII(domain, out, info)
        if (info.errors.any { it.name !in WhatwgHost.Uts46.IGNORED_ERRORS }) return null
        return out.toString()
    }
}
