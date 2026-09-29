package baby.freedom.mobile.wallet

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
 * backing up opens the phrase, so it's held to Show recovery phrase's (#244).
 */
enum class VaultAuthPurpose(val title: String, val subtitle: String, val confirmationRequired: Boolean) {
    CREATE("Create your wallet", "Confirm it’s you to encrypt your new recovery phrase", false),
    IMPORT("Import your wallet", "Confirm it’s you to encrypt your recovery phrase", false),
    UNLOCK("Unlock your wallet", "Confirm it’s you to open your wallet", true),
    REVEAL("Show recovery phrase", "Confirm it’s you to see your recovery phrase", true),
    BACKUP("Back up with Google", "Confirm it’s you to back up your recovery phrase", true),
    RESTORE("Restore your wallet", "Confirm it’s you to restore your wallet from Google backup", true),
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
                        else cont.resumeWithException(VaultAuthFailedException("No key came back from the prompt"))
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
