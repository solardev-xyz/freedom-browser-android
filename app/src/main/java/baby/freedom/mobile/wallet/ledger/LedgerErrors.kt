package baby.freedom.mobile.wallet.ledger

import androidx.annotation.StringRes
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/**
 * Why a Ledger operation didn't finish (#142), in words the wallet shows
 * as they are — desktop's `LEDGER_*` codes (`wallet/ledger/errors.js`),
 * plus the Bluetooth states a phone adds. Never carries anything the
 * device returned beyond its status word.
 */
class LedgerException(val kind: Kind, message: String = kind.message, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind(@StringRes private val messageRes: Int) {
        BLUETOOTH_OFF(R.string.signing_ledger_error_bluetooth_off),
        BLUETOOTH_UNAVAILABLE(R.string.signing_ledger_error_bluetooth_unavailable),
        PERMISSION(R.string.signing_ledger_error_permission),
        NOT_FOUND(R.string.signing_ledger_error_not_found),
        PAIRING_FAILED(R.string.signing_ledger_error_pairing_failed),
        LOCKED(R.string.signing_ledger_error_locked),
        APP_NOT_OPEN(R.string.signing_ledger_error_app_not_open),
        REJECTED(R.string.signing_ledger_error_rejected),
        DISCONNECTED(R.string.signing_ledger_error_disconnected),
        WRONG_DEVICE(R.string.signing_ledger_error_wrong_device),
        BLIND_SIGNING(R.string.signing_ledger_error_blind_signing),
        TIMEOUT(R.string.signing_ledger_error_timeout),
        CANCELLED(R.string.signing_ledger_error_cancelled),
        MISMATCH(R.string.signing_ledger_error_mismatch),
        INVALID_DATA(R.string.signing_ledger_error_invalid_data),
        UNSUPPORTED(R.string.signing_ledger_error_unsupported),
        UNKNOWN(R.string.signing_ledger_error_unknown),
        ;

        val message: String get() = Strings.get(messageRes)
    }

    companion object {
        /**
         * The failure an APDU status word [sw] means (anything but `0x9000`).
         * [blindSigning]: the APDU was one the app refuses with "incorrect
         * data" when Blind signing is off ([LedgerApdus.needsBlindSigning]);
         * anywhere else that word means just that.
         */
        fun forStatus(sw: Int, blindSigning: Boolean = false): LedgerException =
            LedgerException(kindForStatus(sw, blindSigning), cause = StatusWord(sw))

        internal fun kindForStatus(sw: Int, blindSigning: Boolean = false): Kind = when (sw) {
            // Locked (or locked mid-session: "security status not satisfied").
            0x5515, 0x6982 -> Kind.LOCKED
            // Refused on the device.
            0x6985, 0x5501 -> Kind.REJECTED
            // "Incorrect data": while signing what it can only show blind, Blind signing is off;
            // on anything else (an address, a message, a typed-data struct definition), bad data.
            0x6a80 -> if (blindSigning) Kind.BLIND_SIGNING else Kind.INVALID_DATA
            // No app / another app open (dashboard, Bitcoin…): CLA or INS unknown to it.
            0x6511, 0x6d00, 0x6e00, 0x6e01, 0x6d02 -> Kind.APP_NOT_OPEN
            else -> Kind.UNKNOWN
        }
    }

    /** The status word behind a [LedgerException], for the log (it's never secret). */
    class StatusWord(val sw: Int) : Exception("status 0x%04x".format(sw))
}
