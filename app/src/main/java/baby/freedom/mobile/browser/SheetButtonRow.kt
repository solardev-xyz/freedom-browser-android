package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A sheet's action buttons (Reject / Sign, Cancel / Confirm): side by
 * side in equal shares while every label fits its share on one line,
 * stacked full width, in the same order, when one doesn't (#279).
 *
 * A plain `Row` of `weight(1f)` buttons broke labels mid-word at a large
 * font scale and display size — "Rejec / t", "Confi / rm and send" — on
 * the very buttons that approve a signature or a payment.
 */
@Composable
internal fun SheetButtonRow(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val count = measurables.size.coerceAtLeast(1)
        val share = ((width - gapPx * (count - 1)) / count).coerceAtLeast(0)
        val sideBySide = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }
        if (sideBySide) {
            val placeables = measurables.map { it.measure(Constraints(minWidth = share, maxWidth = share)) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                var x = 0
                placeables.forEach {
                    it.place(x, (height - it.height) / 2)
                    x += share + gapPx
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val height = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach {
                    it.place(0, y)
                    y += it.height + gapPx
                }
            }
        }
    }
}
