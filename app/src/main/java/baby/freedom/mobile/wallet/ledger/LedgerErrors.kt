package baby.freedom.mobile.wallet.ledger

import androidx.annotation.StringRes
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Said
import baby.freedom.mobile.l10n.Strings

/**
 * Why a Ledger operation didn't finish (#142), in words the wallet shows
 * as they are — desktop's `LEDGER_*` codes (`wallet/ledger/errors.js`),
 * plus the Bluetooth states a phone adds. Never carries anything the
 * device returned beyond its status word.
 *
 * [words]: said instead of [Kind]'s own line. [message] is in the app
 * language, read when it's shown; [said] also carries the English a site
 * or a peer hears (#280).
 */
class LedgerException(val kind: Kind, private val words: Said? = null, cause: Throwable? = null) : Exception(null, cause) {
    override val message: String get() = said.text

    /** What went wrong, in the app language and in English. */
    val said: Said get() = words ?: kind.said

    /** Whether this says something of its own rather than [Kind]'s line. */
    val ownWords: Boolean get() = words != null

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

        /** [message], and the same in English for a site or a peer (#280). */
        val said: Said get() = Strings.said(messageRes)

        /**
         * Whether [message] already ends by saying nothing went out
         * ("… Nothing was signed."), so a sender doesn't add its own
         * "Nothing was sent." after it. By kind, not by reading the
         * text, which is in the app's language (#280).
         */
        val saysNothingSent: Boolean
            get() = this == TIMEOUT || this == CANCELLED || this == MISMATCH || this == INVALID_DATA
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

    /**
     * Behind a [Kind.WRONG_DEVICE] from an address check (#365): the
     * Ledger approved an address on its screen, but not the one asked
     * about, so that one mustn't be used to receive.
     */
    class ShownDifferent : Exception("approved a different address")

    /** Whether the Ledger approved a different address than the one it was asked to show (#365). */
    val shownDifferent: Boolean get() = cause is ShownDifferent

    /**
     * Behind a [Kind.UNSUPPORTED] from typed data (#476): the Ledger can
     * sign it only by [hashes], which the sheet the user approved didn't
     * show. Nothing was shown on the device.
     */
    class HashesOnly(val hashes: LedgerTypedDataHashes) : Exception("can sign only by hashes not shown")

    /** The hashes typed data can be signed by on this Ledger, once shown on the phone ([HashesOnly]); null for any other failure. */
    val hashesOnly: LedgerTypedDataHashes? get() = (cause as? HashesOnly)?.hashes

    /** The status word behind a [LedgerException], for the log (it's never secret). */
    class StatusWord(val sw: Int) : Exception("status 0x%04x".format(sw))
}
