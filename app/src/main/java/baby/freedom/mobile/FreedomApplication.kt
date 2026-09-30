package baby.freedom.mobile

import android.app.Application
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.TextLocale
import baby.freedom.swarm.SwarmStrings

/**
 * The app's [Application], in every process (the browser, `:node`,
 * `:myotis`, `:tor`): only points [Strings] — and the `:swarmnode`
 * library's [SwarmStrings] — at the app's resources, before any activity,
 * service or receiver runs (#280).
 */
class FreedomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        SwarmStrings.init(this, TextLocale::resources)
    }
}
