package baby.freedom.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class DevicePluralRulesDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = DevicePluralRulesDetector()

    override fun getIssues(): List<Issue> = listOf(DevicePluralRulesDetector.ISSUE)

    fun testDeviceRulesFlagged() {
        lint().files(
            kotlin(
                """
                package androidx.compose.ui.res
                fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String = ""
                """
            ).indented(),
            kotlin(
                """
                package test
                import android.content.res.Resources
                import androidx.compose.ui.res.pluralStringResource

                fun ours(id: Int, count: Int): String = ""
                fun pluralText(id: Int, count: Int, vararg args: Any): String = ""

                fun screen(res: Resources, n: Int) {
                    pluralStringResource(1, n, n)
                    res.getQuantityString(1, n, n)
                    res.getQuantityText(1, n)
                    pluralText(1, n, n)
                    ours(1, n)
                }
                """
            ).indented(),
        ).run().expect(
            """
            src/test/test.kt:9: Error: Plural form chosen by the phone's language, not the text's: use pluralText (Compose) or Strings.plural (#313) [DevicePluralRules]
                pluralStringResource(1, n, n)
                ~~~~~~~~~~~~~~~~~~~~
            src/test/test.kt:10: Error: Plural form chosen by the phone's language, not the text's: use pluralText (Compose) or Strings.plural (#313) [DevicePluralRules]
                res.getQuantityString(1, n, n)
                    ~~~~~~~~~~~~~~~~~
            src/test/test.kt:11: Error: Plural form chosen by the phone's language, not the text's: use pluralText (Compose) or Strings.plural (#313) [DevicePluralRules]
                res.getQuantityText(1, n)
                    ~~~~~~~~~~~~~~~
            3 errors, 0 warnings
            """
        )
    }
}
