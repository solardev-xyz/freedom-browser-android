package baby.freedom.mobile.wallet.ledger

/**
 * Why a Ledger operation didn't finish (#142), in words the wallet shows
 * as they are — desktop's `LEDGER_*` codes (`wallet/ledger/errors.js`),
 * plus the Bluetooth states a phone adds. Never carries anything the
 * device returned beyond its status word.
 */
class LedgerException(val kind: Kind, message: String = kind.message, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind(val message: String) {
        BLUETOOTH_OFF("Bluetooth is off. Turn it on and try again."),
        BLUETOOTH_UNAVAILABLE("This phone has no Bluetooth LE, which a Ledger needs."),
        PERMISSION("Freedom needs the Nearby devices permission to reach your Ledger."),
        NOT_FOUND("Couldn’t reach your Ledger. Unlock it, keep it close and make sure its Bluetooth is on."),
        PAIRING_FAILED("Pairing with the Ledger didn’t finish. Confirm the code on the Ledger and on the phone, then try again."),
        LOCKED("Your Ledger is locked. Unlock it with your PIN."),
        APP_NOT_OPEN("Open the Ethereum app on your Ledger."),
        REJECTED("Rejected on the Ledger."),
        DISCONNECTED("The Ledger disconnected. Keep it close and unlocked, and try again."),
        WRONG_DEVICE("This Ledger doesn’t hold the selected account. Use the Ledger this account was added from."),
        BLIND_SIGNING("The Ledger can’t show all of this in clear, so it only signs it with “Blind signing” on. Turn it on in the Ethereum app’s settings on the Ledger, then try again."),
        TIMEOUT("The Ledger didn’t answer in time. Nothing was signed."),
        CANCELLED("Cancelled. Nothing was signed."),
        MISMATCH("The Ledger’s signature isn’t for what this phone showed. Nothing was used."),
        INVALID_DATA("The Ethereum app on the Ledger refused what it was sent as invalid. Nothing was signed."),
        UNSUPPORTED("The Ethereum app on this Ledger can’t sign this. Update it with Ledger Live and try again."),
        UNKNOWN("Ledger error. Unlock the Ledger, open the Ethereum app and try again."),
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
