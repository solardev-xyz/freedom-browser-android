package baby.freedom.mobile.wallet

import java.util.Base64
import org.json.JSONObject

/**
 * How the vault's Keystore key is guarded (#76), recorded next to the
 * ciphertext and honoured on every unlock — iOS's `VaultSecurityLevel`.
 */
enum class VaultProtection(val wire: String) {
    /**
     * The key needs the user — a strong biometric or the device PIN,
     * pattern or password — for every use, through BiometricPrompt.
     */
    SCREEN_LOCK("screen-lock"),

    /**
     * The phone had no screen lock when the wallet was made, and Android
     * can't make an authentication-bound key without one: the key is
     * still hardware-backed and never leaves the phone, but anyone who
     * has the phone can unlock the wallet. iOS's `deviceBound` fallback;
     * the wallet page says so for as long as it holds.
     */
    DEVICE_ONLY("device-only"),
    ;

    companion object {
        fun fromWire(s: String): VaultProtection? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * The vault as stored on disk: the recovery phrase sealed with AES-GCM
 * under the Keystore key, plus what isn't secret — how the key is
 * guarded, whether it sits in StrongBox, and whether the user has seen
 * the phrase since it was made (the backup reminder). There is no
 * plaintext copy of the phrase anywhere; [ciphertext] can only be opened
 * by the Keystore key, which can't leave the phone's secure hardware.
 */
class VaultRecord(
    val protection: VaultProtection,
    val strongBox: Boolean,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val backedUp: Boolean,
) {
    fun withBackedUp(backedUp: Boolean) = VaultRecord(protection, strongBox, iv, ciphertext, backedUp)

    fun encode(): String = JSONObject()
        .put("version", VERSION)
        .put("protection", protection.wire)
        .put("strongBox", strongBox)
        .put("iv", Base64.getEncoder().encodeToString(iv))
        .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        .put("backedUp", backedUp)
        .toString()

    companion object {
        const val VERSION = 1

        /** Null for anything that isn't a version-1 vault file (the vault is then unreadable, not empty). */
        fun decode(text: String): VaultRecord? = try {
            val o = JSONObject(text)
            if (o.getInt("version") != VERSION) {
                null
            } else {
                VaultRecord(
                    protection = VaultProtection.fromWire(o.getString("protection")) ?: return null,
                    strongBox = o.getBoolean("strongBox"),
                    iv = Base64.getDecoder().decode(o.getString("iv")),
                    ciphertext = Base64.getDecoder().decode(o.getString("ciphertext")),
                    backedUp = o.optBoolean("backedUp", false),
                ).takeIf { it.iv.isNotEmpty() && it.ciphertext.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
