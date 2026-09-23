package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.UserHashTextStyle
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
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
    Column(modifier = modifier.fillMaxWidth().padding(VmSpacing.lg)) {
        VmText(text = stringResource(R.string.vm_safety_number_title), style = VmTheme.typography.bodyLgMedium)
        VmText(
            text = fingerprint,
            style = UserHashTextStyle,
            color = VmTheme.colors.textPrimary,
            modifier = Modifier.padding(top = VmSpacing.sm),
        )
    }
}

private fun safetyFingerprint(local: ByteArray, remote: ByteArray): String {
    val sorted = listOf(local, remote).sortedWith(compareBy { it.contentHashCode() })
    val digest = MessageDigest.getInstance("SHA-256").digest(sorted[0] + sorted[1])
    return digest.take(8).joinToString(" ") { "%02X".format(it) }
}
