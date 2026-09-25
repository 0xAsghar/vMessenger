package ir.vmessenger.data.nodesetup

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Makes the full BouncyCastle the JCA's "BC" before sshj runs.
 *
 * Android registers a cut-down BouncyCastle under that name, without the curves and ciphers SSH
 * needs; sshj finds a provider called "BC" and uses it. The full one takes its place at the same
 * position in the provider list, so nothing else changes order. Done lazily, the first time a node
 * is set up — not at app start — so nothing outside "New node" runs with it swapped.
 */
object SshCryptoProvider {
    @Volatile
    private var installed = false

    fun ensureInstalled() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (existing?.javaClass != BouncyCastleProvider::class.java) {
                val position = Security.getProviders().indexOfFirst { it.name == BouncyCastleProvider.PROVIDER_NAME }
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                // insertProviderAt is 1-based; a missing "BC" goes last.
                val at = if (position >= 0) position + 1 else Security.getProviders().size + 1
                Security.insertProviderAt(BouncyCastleProvider(), at)
            }
            installed = true
        }
    }
}
