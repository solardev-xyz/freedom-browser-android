package baby.freedom.mobile.browser

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldCheckTest {
    @Test
    fun `jitter inside the slop is a hold`() {
        val hold = HoldCheck(slop = 10f)
        hold.move(Offset(3f, 2f))
        hold.move(Offset(-4f, 1f))
        hold.move(Offset(2f, -5f))
        assertTrue(hold.isHold)
    }

    @Test
    fun `a drag out and back to the start is not a hold`() {
        val hold = HoldCheck(slop = 10f)
        hold.move(Offset(200f, 0f)) // onto the next card
        hold.move(Offset(-199f, 0f)) // and back
        assertFalse(hold.isHold)
    }

    @Test
    fun `leaving the slop by small steps is not a hold`() {
        val hold = HoldCheck(slop = 10f)
        repeat(4) { hold.move(Offset(0f, 3f)) }
        repeat(4) { hold.move(Offset(0f, -3f)) }
        assertFalse(hold.isHold)
    }
}
