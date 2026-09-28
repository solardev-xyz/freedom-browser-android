package baby.freedom.mobile.browser

import java.lang.ref.WeakReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WeakValueCacheTest {
    private class Key
    /** Like [PrivateWindowContext]: the value strongly references its key. */
    private class Wrapper(val base: Key)

    @Test
    fun sameValueWhileHeld() {
        val cache = WeakValueCache<Key, Wrapper>(::Wrapper)
        val key = Key()
        val first = cache[key]
        assertSame(first, cache[key])
        assertSame(key, first.base)
    }

    @Test
    fun droppedKeyIsCollectedEvenThoughItsValueReferencesIt() {
        val cache = WeakValueCache<Key, Wrapper>(::Wrapper)
        val keyRef = fill(cache)
        awaitCleared(keyRef)
        assertNull("cache kept a dropped key reachable", keyRef.get())
    }

    // In its own frame so no local keeps the key or its wrapper alive.
    private fun fill(cache: WeakValueCache<Key, Wrapper>): WeakReference<Key> {
        val key = Key()
        cache[key]
        return WeakReference(key)
    }

    private fun awaitCleared(ref: WeakReference<*>) {
        repeat(50) {
            if (ref.get() == null) return
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }
    }
}
