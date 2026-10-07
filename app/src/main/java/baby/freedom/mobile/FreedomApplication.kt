package baby.freedom.mobile

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import baby.freedom.mobile.browser.AdblockLocaleDefaults
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
        initSwarmStrings(this)
        // The phone's languages can change while the app runs (locale is
        // in the Activity's configChanges): re-read the defaults that hang
        // off them (#405 R1-M3).
        registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = AdblockLocaleDefaults.refresh()
            override fun onLowMemory() = Unit
        })
    }

    companion object {
        /**
         * Points [SwarmStrings] at [context]'s resources, its counts resolved
         * exactly as the app's own ([TextLocale.plural], #313 R3-M1).
         */
        fun initSwarmStrings(context: Context) {
            SwarmStrings.init(context) { c, id, count, args -> TextLocale.plural(c, id, count, *args) }
        }
    }
}
