package baby.freedom.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USwitchClauseExpressionWithBody
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UYieldExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.skipParenthesizedExprDown

/**
 * Flags a string literal (or a string template / concatenation with a
 * literal part) that contains a letter where it becomes user-visible
 * text: Compose `Text`, `contentDescription`, toasts, snackbars,
 * notifications and `AlertDialog.Builder` (#280). Such text belongs in
 * `res/values/strings*.xml`, or it can never be translated.
 */
class HardcodedUiTextDetector : Detector(), SourceCodeScanner {

    /**
     * A user-visible text argument: parameter [index] (counting only
     * value parameters) or, in Kotlin, the argument named [name].
     */
    private class TextParam(val index: Int, val name: String?)

    /** Methods called [method] on one of [owners] whose [params] are UI text. */
    private class Rule(val owners: Set<String>, val method: String, val params: List<TextParam>)

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UCallExpression::class.java, UBinaryExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        // Belt and braces: the issue's scope already leaves test sources out.
        if (context.isTestSource) return null
        return object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) = checkCall(context, node)

            override fun visitBinaryExpression(node: UBinaryExpression) {
                // `semantics { contentDescription = "…" }`, `view.contentDescription = "…"`
                if (node.operator != UastBinaryOperator.ASSIGN) return
                val target = when (val left = node.leftOperand.skipParenthesizedExprDown()) {
                    is USimpleNameReferenceExpression -> left.identifier
                    is UQualifiedReferenceExpression ->
                        (left.selector as? USimpleNameReferenceExpression)?.identifier
                    else -> null
                }
                if (target == CONTENT_DESCRIPTION) checkText(context, node.rightOperand)
            }
        }
    }

    private fun checkCall(context: JavaContext, call: UCallExpression) {
        val args = call.valueArguments
        // Cheap test first: resolving every call in the app is what costs.
        if (args.none { findLetterLiteral(it) != null }) return
        val method = call.resolve()
        val mapping = method?.let { context.evaluator.computeArgumentMapping(call, it) }.orEmpty()

        // Each argument's (index, name): from the resolved parameter where
        // there is one, else from the source (a positional argument's
        // place in the list; a Kotlin named argument's name).
        fun indexOf(arg: UExpression): Int {
            val param = mapping[arg]
            if (param != null && method != null) return method.parameterList.getParameterIndex(param)
            return if (argumentName(arg) == null) args.indexOf(arg) else -1
        }
        fun nameOf(arg: UExpression): String? = argumentName(arg) ?: mapping[arg]?.name

        // contentDescription = "…" on any call (Icon, Image, a custom composable).
        for (arg in args) {
            if (nameOf(arg) == CONTENT_DESCRIPTION) checkText(context, arg)
        }

        val rules = matchingRules(context, call, method)
        for (rule in rules) {
            for (arg in args) {
                val index = indexOf(arg)
                val name = nameOf(arg)
                if (rule.params.any { p -> (p.name != null && p.name == name) || p.index == index }) {
                    if (name != CONTENT_DESCRIPTION) checkText(context, arg)
                }
            }
        }
    }

    private fun matchingRules(context: JavaContext, call: UCallExpression, method: PsiMethod?): List<Rule> {
        if (call.kind == UastCallKind.CONSTRUCTOR_CALL) {
            val cls = method?.containingClass?.qualifiedName
                ?: call.classReference?.resolvedName
                ?: return emptyList()
            return CONSTRUCTOR_RULES.filter { cls in it.owners }
        }
        val name = call.methodName ?: call.methodIdentifier?.name ?: return emptyList()
        val candidates = RULES.filter { it.method == name }
        if (candidates.isEmpty()) return emptyList()
        if (method == null) {
            // Unresolved (a broken classpath, a stub missing): only Compose
            // Text is recognisable by its name alone.
            return if (name == "Text") candidates.filter { it.method == "Text" } else emptyList()
        }
        val owner = method.containingClass ?: return emptyList()
        return candidates.filter { rule ->
            rule.owners.any { o -> owner.qualifiedName == o || context.evaluator.extendsClass(owner, o, false) }
        }
    }

    /** Reports [expression] if it is, or is built from, a literal with a letter in it. */
    private fun checkText(context: JavaContext, expression: UExpression) {
        if (findLetterLiteral(expression) == null) return
        context.report(ISSUE, expression, context.getLocation(expression), MESSAGE)
    }

    /**
     * The first string literal with a letter in [expression]: the literal
     * itself, a literal part of a template (`"$n tabs"`) or of a `+`
     * concatenation, or a branch of an `if` or `when`.
     */
    private fun findLetterLiteral(expression: UExpression?): UExpression? {
        return when (val e = expression?.skipParenthesizedExprDown()) {
            is ULiteralExpression -> (e.value as? String)?.takeIf { s -> s.any(Char::isLetter) }?.let { e }
            is UPolyadicExpression ->
                if (e.operator == UastBinaryOperator.PLUS) {
                    e.operands.firstNotNullOfOrNull { findLetterLiteral(it) }
                } else {
                    null
                }
            is UIfExpression -> findLetterLiteral(e.thenExpression) ?: findLetterLiteral(e.elseExpression)
            is USwitchExpression -> e.body.expressions.firstNotNullOfOrNull { clause ->
                (clause as? USwitchClauseExpressionWithBody)?.let { findLetterLiteral(it.body.expressions.lastOrNull()) }
            }
            is UYieldExpression -> findLetterLiteral(e.expression)
            is UParenthesizedExpression -> findLetterLiteral(e.expression)
            // `if (…) { "a" } else { "b" }`: a branch's value is its last expression.
            is UBlockExpression -> findLetterLiteral(e.expressions.lastOrNull())
            else -> null
        }
    }

    /** The name of a Kotlin named argument (`text = …`), or null. */
    private fun argumentName(arg: UExpression): String? =
        (arg.sourcePsi?.parent as? KtValueArgument)?.getArgumentName()?.asName?.identifier

    companion object {
        private const val CONTENT_DESCRIPTION = "contentDescription"

        private const val MESSAGE =
            "Hardcoded UI text: move it to `res/values/strings*.xml` and use " +
                "`stringResource`/`Strings.get` (#280)"

        private val COMPOSE_TEXT = setOf(
            "androidx.compose.material3.TextKt",
            "androidx.compose.material.TextKt",
            "androidx.compose.foundation.text.BasicTextKt",
        )
        private val SNACKBAR_HOST_STATE = setOf(
            "androidx.compose.material3.SnackbarHostState",
            "androidx.compose.material.SnackbarHostState",
        )
        private val NOTIFICATION_BUILDER = setOf(
            "androidx.core.app.NotificationCompat.Builder",
            "android.app.Notification.Builder",
        )
        private val ALERT_DIALOG_BUILDER = setOf(
            "android.app.AlertDialog.Builder",
            "androidx.appcompat.app.AlertDialog.Builder",
        )

        private val RULES = listOf(
            Rule(COMPOSE_TEXT, "Text", listOf(TextParam(0, "text"))),
            Rule(COMPOSE_TEXT, "BasicText", listOf(TextParam(0, "text"))),
            Rule(setOf("android.widget.Toast"), "makeText", listOf(TextParam(1, "text"))),
            Rule(
                SNACKBAR_HOST_STATE, "showSnackbar",
                listOf(TextParam(0, "message"), TextParam(1, "actionLabel")),
            ),
            Rule(NOTIFICATION_BUILDER, "setContentTitle", listOf(TextParam(0, null))),
            Rule(NOTIFICATION_BUILDER, "setContentText", listOf(TextParam(0, null))),
            Rule(NOTIFICATION_BUILDER, "setSubText", listOf(TextParam(0, null))),
            Rule(NOTIFICATION_BUILDER, "setTicker", listOf(TextParam(0, null))),
            Rule(NOTIFICATION_BUILDER, "addAction", listOf(TextParam(1, null))),
            Rule(ALERT_DIALOG_BUILDER, "setTitle", listOf(TextParam(0, null))),
            Rule(ALERT_DIALOG_BUILDER, "setMessage", listOf(TextParam(0, null))),
            Rule(ALERT_DIALOG_BUILDER, "setPositiveButton", listOf(TextParam(0, null))),
            Rule(ALERT_DIALOG_BUILDER, "setNegativeButton", listOf(TextParam(0, null))),
            Rule(ALERT_DIALOG_BUILDER, "setNeutralButton", listOf(TextParam(0, null))),
        )

        private val CONSTRUCTOR_RULES = listOf(
            // NotificationChannel(id, name, importance): the name shows in
            // the system's notification settings.
            Rule(setOf("android.app.NotificationChannel"), "<init>", listOf(TextParam(1, null))),
        )

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "HardcodedUiText",
            briefDescription = "Hardcoded user-visible text",
            explanation = """
                Text the user sees (Compose `Text`, `contentDescription`, toasts, \
                snackbars, notifications, dialogs) must come from string resources so \
                the app can be translated by adding a `values-<lang>` folder (#280). \
                Put the text in `res/values/strings_<area>.xml` and use \
                `stringResource(R.string.x)` in Compose or `Strings.get(R.string.x)` \
                elsewhere; counts go through `<plurals>`.

                Text that isn't language (a symbol, a sample address, a protocol name \
                on its own) can be suppressed with `@Suppress("HardcodedUiText")` and \
                a comment saying why. See docs/localisation.md.
                """,
            category = Category.I18N,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(HardcodedUiTextDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
