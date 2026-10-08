package baby.freedom.mobile.browser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tab's default video poster is a tiny, fully transparent bitmap,
 * created once: a poster-less `<video>` stays blank until its first
 * frame instead of showing WebView's grey play triangle.
 */
@RunWith(AndroidJUnit4::class)
class VideoPosterDeviceTest {
    @Test
    fun blankPosterIsOneTransparentPixel() {
        val poster = VideoPoster.blank
        assertEquals(1, poster.width)
        assertEquals(1, poster.height)
        assertEquals(Bitmap.Config.ARGB_8888, poster.config)
        assertEquals(0, Color.alpha(poster.getPixel(0, 0)))
    }

    @Test
    fun blankPosterIsCreatedOnce() {
        assertSame(VideoPoster.blank, VideoPoster.blank)
    }
}
