package baby.freedom.mobile.node

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import baby.freedom.swarm.MyotisInfo

/**
 * A binding to [MyotisService] that never starts it (#329): the `:node`
 * process's, so the Swarm node's Gnosis reads reach the light client
 * ([MyotisLink]) whenever the user has it running, and nothing changes
 * when they don't.
 *
 * Bound without `BIND_AUTO_CREATE`: it connects when the UI's binding
 * brings `:myotis` up and disconnects when that process exits, and it
 * neither creates the service nor keeps it alive — turning the light
 * client off still stops it. It only reads: which chains run, and when
 * the engines sleep, stay the UI's to say.
 */
internal class MyotisReadBinding(private val context: Context) {
    @Volatile
    private var bound = false
    private var service: IMyotisService? = null

    private val callback = object : IMyotisCallback.Stub() {
        override fun onMyotisStateChanged(info: MyotisInfo?) {
            if (info != null && bound) MyotisLink.onState(this@MyotisReadBinding, info)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IMyotisService.Stub.asInterface(binder) ?: return
            service = s
            // Before registering: the first state arrives on registration.
            MyotisLink.connected(this@MyotisReadBinding, s)
            runCatching { s.registerCallback(callback) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            MyotisLink.disconnected(this@MyotisReadBinding)
        }
    }

    fun bind() {
        if (bound) return
        bound = runCatching {
            context.bindService(Intent(context, MyotisService::class.java), connection, 0)
        }.getOrDefault(false)
        if (!bound) runCatching { context.unbindService(connection) }
    }

    fun unbind() {
        if (!bound) return
        bound = false
        runCatching { service?.unregisterCallback(callback) }
        runCatching { context.unbindService(connection) }
        service = null
        MyotisLink.disconnected(this)
    }
}
