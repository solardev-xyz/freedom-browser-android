package baby.freedom.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Flags Compose's `pluralStringResource` and `Resources.getQuantityString`
 * / `getQuantityText` (#313 R1-F1). Both pick a plural form by the
 * phone's language, not the language of the text: on a phone set to a
 * language Freedom has no translation for, the English text comes out as
 * "1 matches" (Japanese rules) or "0 match" (French). The app's
 * `pluralText` / `Strings.plural` / `TextLocale.plural` use the text's own
 * language.
 */
class DevicePluralRulesDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames(): List<String> =
        listOf("pluralStringResource", "getQuantityString", "getQuantityText")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (context.isTestSource) return
        val owner = method.containingClass?.qualifiedName ?: return
        val flagged = when (method.name) {
            "pluralStringResource" -> owner.startsWith("androidx.compose.ui.res.")
            else -> owner == "android.content.res.Resources" || context.evaluator.extendsClass(
                method.containingClass, "android.content.res.Resources", false,
            )
        }
        if (!flagged) return
        context.report(
            ISSUE,
            node,
            context.getNameLocation(node),
            "Plural form chosen by the phone's language, not the text's: use pluralText " +
                "(Compose) or Strings.plural (#313)",
        )
    }

    companion object {
        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "DevicePluralRules",
            briefDescription = "Plural form chosen by the phone's language",
            explanation = """
                Android picks a `<plurals>` form with the plural rules of the \
                configuration's first locale, whichever `values-<lang>` folder the \
                text came from. When Freedom has no translation for the phone's \
                language, the text is English but the rules aren't: Japanese has \
                only `other` ("1 matches"), French puts 0 in `one` ("0 match"). \
                Use `pluralText(R.plurals.x, n, n)` in Compose and \
                `Strings.plural(R.plurals.x, n, n)` elsewhere; they use the rules \
                of the language named by `l10n_language`. See docs/localisation.md.
                """,
            category = Category.I18N,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(DevicePluralRulesDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
