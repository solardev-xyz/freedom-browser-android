package baby.freedom.mobile.node

import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MyotisLink]'s per-owner bookkeeping (#101): more than one
 * `MainActivity` can be bound at once, and one of them unbinding must
 * not take the light client away from the others.
 */
class MyotisBindingsTest {
    private val running = MyotisInfo(status = MyotisStatus.Running)
    private val starting = MyotisInfo(status = MyotisStatus.Starting)

    private fun bindings() = MyotisBindings<String> { it.status == MyotisStatus.Running }

    @Test
    fun `ready once a bound owner reports ready, not before`() {
        val b = bindings()
        val a = Any()
        b.connected(a, "service-a")
        assertNull(b.generation)
        assertEquals("service-a", b.service)
        b.onState(a, running)
        assertNotNull(b.generation)
    }

    @Test
    fun `a second instance's unbind leaves the first one's binding ready`() {
        val b = bindings()
        val first = Any()
        val second = Any()
        b.connected(first, "service-a")
        b.onState(first, running)
        val readyWithFirst = b.generation
        assertNotNull(readyWithFirst)

        // An App Link opens a second instance, which binds too...
        b.connected(second, "service-b")
        b.onState(second, running)
        assertEquals("service-b", b.service)
        assertNotNull(b.generation)
        // ...and is backed out of: the first is still bound and serving.
        b.disconnected(second)
        assertEquals("service-a", b.service)
        assertNotNull(b.generation)
        // It keeps publishing states, and stays ready.
        val now = b.generation
        b.onState(first, running)
        assertEquals(now, b.generation)
        // Only its own unbind ends it.
        b.disconnected(first)
        assertNull(b.service)
        assertNull(b.generation)
    }

    @Test
    fun `a freshly bound instance that isn't ready yet doesn't hide a ready one`() {
        val b = bindings()
        val first = Any()
        val second = Any()
        b.connected(first, "service-a")
        b.onState(first, running)
        val gen = b.generation
        b.connected(second, "service-b")
        b.onState(second, starting)
        assertEquals("service-a", b.service)
        assertEquals(gen, b.generation)
    }

    @Test
    fun `a late state from an owner that already unbound is ignored`() {
        val b = bindings()
        val first = Any()
        val second = Any()
        b.connected(first, "service-a")
        b.disconnected(first)
        b.onState(first, running)
        assertNull(b.generation)
        b.connected(second, "service-b")
        assertNull(b.generation)
    }

    @Test
    fun `each new stretch of readiness has a new generation`() {
        val b = bindings()
        val a = Any()
        b.connected(a, "s")
        b.onState(a, running)
        val one = b.generation
        b.onState(a, starting)
        assertNull(b.generation)
        b.onState(a, running)
        assertNotEquals(one, b.generation)
        // A rebind (`:myotis` restarted) is a new stretch too.
        val two = b.generation
        b.disconnected(a)
        b.connected(a, "s2")
        b.onState(a, running)
        assertNotEquals(two, b.generation)
    }
}
