package baby.freedom.mobile.wallet

import baby.freedom.mobile.data.KeystoreKey
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The wallet vault and the RPC API key store (#169) each keep an AES key
 * in the Android Keystore, looked up by alias. Removing the wallet
 * deletes its key by alias, and the API key store creates a key under
 * its alias whenever none is there. One shared alias would let removing
 * the wallet destroy the API keys, and let the API store use (or
 * pre-create, without the user-auth requirement) the wallet's key.
 */
class KeystoreAliasTest {
    @Test
    fun `wallet vault and RPC API keys use distinct Keystore aliases`() {
        assertNotEquals(KeystoreKey.ALIAS, KeystoreVaultStore.KEY_ALIAS)
    }

    /**
     * The node identity key (#77) is made without user authentication and
     * deleted on Remove wallet: sharing either other alias would hand the
     * vault's phrase to an unauthenticated key, or wipe the API keys.
     */
    @Test
    fun `node identity key has its own alias`() {
        assertNotEquals(KeystoreNodeKeys.ALIAS, KeystoreVaultStore.KEY_ALIAS)
        assertNotEquals(KeystoreNodeKeys.ALIAS, KeystoreKey.ALIAS)
    }

    /** The AI providers' keys (#573) have their own alias too: Remove wallet must not take them, nor they the wallet's. */
    @Test
    fun `AI provider keys have their own alias`() {
        val aliases = listOf(
            baby.freedom.mobile.agent.AgentStore.KEY_ALIAS,
            KeystoreKey.ALIAS,
            KeystoreVaultStore.KEY_ALIAS,
            KeystoreNodeKeys.ALIAS,
        )
        org.junit.Assert.assertEquals(aliases.size, aliases.toSet().size)
    }
}
