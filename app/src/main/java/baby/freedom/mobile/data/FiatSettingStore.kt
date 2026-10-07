package baby.freedom.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import baby.freedom.mobile.wallet.FiatCurrency
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * Wallet settings → Show prices (#439): the one choice, in its own
 * [DataStore] (`freedom_wallet_prices`). Never throws for storage
 * trouble: a file that can't be read reads as Off — the private default
 * — and a failed write is logged; the choice still holds this session.
 */
class FiatSettingStore internal constructor(private val store: DataStore<Preferences>) {
    suspend fun load(): FiatCurrency = try {
        FiatCurrency.parse(store.data.first()[KEY])
    } catch (e: IOException) {
        Log.w(TAG, "reading the price setting failed; treating as off", e)
        FiatCurrency.OFF
    }

    suspend fun save(value: FiatCurrency) {
        try {
            store.edit { if (value == FiatCurrency.OFF) it.remove(KEY) else it[KEY] = value.name }
        } catch (e: IOException) {
            Log.w(TAG, "writing the price setting failed", e)
        }
    }

    companion object {
        private const val TAG = "FiatSettingStore"
        private val KEY = stringPreferencesKey("show_prices")

        private val Context.fiatSettingStore by preferencesDataStore(
            name = "freedom_wallet_prices",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: FiatSettingStore? = null

        fun get(context: Context): FiatSettingStore =
            instance ?: synchronized(this) {
                instance ?: FiatSettingStore(context.applicationContext.fiatSettingStore).also { instance = it }
            }
    }
}
