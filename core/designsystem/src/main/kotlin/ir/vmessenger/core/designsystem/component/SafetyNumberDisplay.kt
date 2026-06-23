package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

@Composable
fun SafetyNumberDisplay(
    localPublicKey: ByteArray,
    remotePublicKey: ByteArray,
    modifier: Modifier = Modifier,
) {
    val fingerprint = remember(localPublicKey, remotePublicKey) {
        safetyFingerprint(localPublicKey, remotePublicKey)
    }
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "شماره امنیتی", style = MaterialTheme.typography.titleMedium)
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun safetyFingerprint(local: ByteArray, remote: ByteArray): String {
    val sorted = listOf(local, remote).sortedWith(compareBy { it.contentHashCode() })
    val digest = MessageDigest.getInstance("SHA-256").digest(sorted[0] + sorted[1])
    return digest.take(8).joinToString(" ") { "%02X".format(it) }
}
