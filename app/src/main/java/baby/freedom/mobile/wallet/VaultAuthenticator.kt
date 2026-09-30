package baby.freedom.mobile.wallet

import androidx.annotation.StringRes
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.R
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * What the user is being asked to authenticate for; the prompt's wording.
 *
 * [confirmationRequired]: whether a passive biometric (a face) must also be
 * confirmed with a tap. Opening the wallet or showing the phrase must not
 * happen just because the phone was pointed at its owner's face by someone
 * else holding it (#229); sealing a new phrase gives nobody anything. Restoring
 * from Google backup leaves an unlocked wallet, so it's held to Unlock's bar;
 * backing up opens the phrase, so it's held to Show recovery phrase's (#244),
 * and so is showing one account's private key (#323).
 */
enum class VaultAuthPurpose(
    @StringRes private val titleRes: Int,
    @StringRes private val subtitleRes: Int,
    val confirmationRequired: Boolean,
) {
    CREATE(R.string.wallet_auth_create_title, R.string.wallet_auth_create_subtitle, false),
    IMPORT(R.string.wallet_auth_import_title, R.string.wallet_auth_import_subtitle, false),
    UNLOCK(R.string.wallet_auth_unlock_title, R.string.wallet_auth_unlock_subtitle, true),
    REVEAL(R.string.wallet_auth_reveal_title, R.string.wallet_auth_reveal_subtitle, true),
    BACKUP(R.string.wallet_auth_backup_title, R.string.wallet_auth_backup_subtitle, true),
    RESTORE(R.string.wallet_auth_restore_title, R.string.wallet_auth_restore_subtitle, true),
    EXPORT_KEY(R.string.wallet_auth_export_key_title, R.string.wallet_auth_export_key_subtitle, true),
    ;

    val title: String get() = Strings.get(titleRes)
    val subtitle: String get() = Strings.get(subtitleRes)
}

/** The user backed out of the prompt (or the system dismissed it); not an error to show. */
class VaultAuthCancelledException : Exception("authentication cancelled")

/** The prompt ended without a result, e.g. too many attempts; [message] is Android's explanation. */
class VaultAuthFailedException(message: String) : Exception(message)

/**
 * Unlocks a Keystore cipher for one operation. [BiometricVaultAuthenticator]
 * is the real one; tests hand the cipher straight back.
 */
fun interface VaultAuthenticator {
    suspend fun authenticate(cipher: Cipher, purpose: VaultAuthPurpose): Cipher
}

/**
 * BiometricPrompt over the vault key's cipher (#76): a strong biometric,
 * or the device PIN, pattern or password — Android offers whichever the
 * user has, and the credential is always there as the fallback. The
 * cipher goes in as the prompt's `CryptoObject`, so it's this operation
 * the Keystore authorises, not a time window.
 *
 * The framework prompt (API 30+, the app's minSdk) rather than androidx
 * biometric: it takes any Context, while androidx's needs a
 * FragmentActivity, and the app is a plain ComponentActivity.
 */
class BiometricVaultAuthenticator(private val context: Context) : VaultAuthenticator {
    override suspend fun authenticate(cipher: Cipher, purpose: VaultAuthPurpose): Cipher =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            val prompt = BiometricPrompt.Builder(context)
                .setTitle(purpose.title)
                .setSubtitle(purpose.subtitle)
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .setConfirmationRequired(purpose.confirmationRequired)
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(cipher),
                cancel,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authed = result.cryptoObject?.cipher
                        if (!cont.isActive) return
                        if (authed != null) cont.resume(authed)
                        else cont.resumeWithException(VaultAuthFailedException(Strings.get(R.string.wallet_auth_no_key)))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        cont.resumeWithException(
                            if (errorCode in CANCEL_CODES) VaultAuthCancelledException()
                            else VaultAuthFailedException(errString.toString()),
                        )
                    }
                    // onAuthenticationFailed: one rejected finger; the prompt stays up.
                },
            )
        }

    private companion object {
        val CANCEL_CODES = setOf(
            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
            BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        )
    }
}
