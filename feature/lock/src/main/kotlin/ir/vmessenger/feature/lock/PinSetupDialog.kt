package ir.vmessenger.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import ir.vmessenger.core.crypto.lock.PinVerifier
import ir.vmessenger.core.designsystem.component.VmDialog
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.core.designsystem.R as DesignR

/**
 * Asks for a new PIN twice and refuses a mismatch.
 *
 * [onDone] receives a fresh [CharArray] the caller owns and must zero after use, or null when the
 * user backed out — the same contract the backup passphrase dialog hands out.
 *
 * Entry is the keypad rather than a text field on purpose. The verifier hashes the characters it
 * is given, so a PIN set as Persian `۱۲۳۴` from a system keyboard and retyped as ASCII `1234` on
 * the lock screen would be one PIN to the user and two different secrets to Argon2id. One digit
 * source for both screens is the only place that mistake can be prevented rather than handled.
 */
@Composable
fun PinSetupDialog(onDone: (CharArray?) -> Unit) {
    val state = remember { PinSetupState() }
    DisposableEffect(state) {
        onDispose { state.clear() }
    }
    VmDialog(
        onDismissRequest = { onDone(null) },
        title = stringResource(R.string.pin_setup_title),
        buttons = {
            VmTextButton(text = stringResource(DesignR.string.vm_cancel), onClick = { onDone(null) })
            VmTextButton(
                text = stringResource(if (state.confirming) R.string.pin_setup_save else R.string.pin_setup_next),
                enabled = state.entry.isSubmittable,
                onClick = { state.submit()?.let(onDone) },
            )
        },
    ) {
        PinSetupBody(state = state)
    }
}

@Composable
private fun PinSetupBody(state: PinSetupState) {
    Column(
        verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VmText(
            text = if (state.confirming) {
                stringResource(R.string.pin_setup_confirm)
            } else {
                stringResource(R.string.pin_setup_enter, persian(PinVerifier.MIN_PIN_LENGTH))
            },
            textAlign = TextAlign.Center,
        )
        if (state.mismatch) {
            VmText(
                text = stringResource(R.string.pin_setup_mismatch),
                color = VmTheme.colors.textCritical,
                textAlign = TextAlign.Center,
            )
        }
        PinDots(length = state.entry.length)
        PinKeypad(entry = state.entry, enabled = true, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The two steps of setting a PIN, and the ownership of both arrays while it happens.
 *
 * The first PIN has to be held to compare the second against it, which is the one moment this
 * module keeps a PIN alive across a user action — so it is zeroed on every path out: a match, a
 * mismatch, and the dialog simply going away.
 */
@Stable
private class PinSetupState {
    val entry = PinEntry()

    private var firstPin: CharArray? by mutableStateOf(null)

    var mismatch by mutableStateOf(false)
        private set

    val confirming: Boolean get() = firstPin != null

    /** Returns the confirmed PIN, or null while there is still a step to go. */
    fun submit(): CharArray? {
        val submitted = entry.take()
        val previous = firstPin
        firstPin = null
        if (previous == null) {
            mismatch = false
            firstPin = submitted
            return null
        }
        val matched = previous.contentEquals(submitted)
        previous.fill(ZEROED)
        mismatch = !matched
        if (!matched) submitted.fill(ZEROED)
        return submitted.takeIf { matched }
    }

    fun clear() {
        entry.clear()
        firstPin?.fill(ZEROED)
        firstPin = null
    }
}
