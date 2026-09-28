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

    override fun toUnicode(domain: String): String? {
        val out = StringBuilder()
        val info = IDNA.Info()
        idna.nameToUnicode(domain, out, info)
        if (info.hasErrors()) return null
        return out.toString()
    }

    override fun map(domain: String): String? {
        val out = StringBuilder()
        idna.nameToUnicode(domain, out, IDNA.Info())
        return out.toString().takeIf { '\uFFFD' !in it }
    }
}
