package baby.freedom.mobile.wallet.ledger

import android.content.Context

/** A release build reaches a Ledger over Bluetooth only: no emulator links (see the debug source set). */
internal object LedgerDevLinks {
    fun devices(context: Context): List<LedgerDevice> = emptyList()

    fun handles(id: String): Boolean = false

    suspend fun open(context: Context, id: String): LedgerLink? = null
}
