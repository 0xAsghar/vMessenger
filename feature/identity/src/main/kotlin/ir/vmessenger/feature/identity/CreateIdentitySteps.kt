package ir.vmessenger.feature.identity

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmProgressIndicator
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.model.RestoreSummary
import ir.vmessenger.core.designsystem.R as DesignR

/** A backup is an opaque blob; some pickers only offer it under the generic type. */
private val BACKUP_MIME_TYPES = arrayOf("application/octet-stream", "*/*")

/** The first thing anyone sees of vMessenger: the mark, the promise, and two ways in. */
@Composable
internal fun CreateIdentityIntro(
    onContinue: () -> Unit,
    onRestoreFile: (Uri) -> Unit,
) {
    val openBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onRestoreFile) }
    LogoMark()
    OnboardingHeading(
        title = stringResource(R.string.create_identity_title),
        body = stringResource(R.string.create_identity_body),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        VmButton(
            text = stringResource(R.string.create_identity_action),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        VmOutlinedButton(
            text = stringResource(R.string.restore_backup_action),
            onClick = { openBackupDocument.launch(BACKUP_MIME_TYPES) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The logo keeps its own fill — the drawable ships a light and a dark variant already, and a
 * tint collapses its two interleaved strands into one silhouette. The disc behind it is what
 * turns a bare glyph into something to look at.
 */
@Composable
private fun LogoMark() {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(VmTheme.colors.bgSubtle)
            .padding(VmSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(DesignR.drawable.ic_vmessenger_logo),
            contentDescription = stringResource(DesignR.string.vmessenger_logo),
            modifier = Modifier.size(VmSizes.avatarLg),
        )
    }
}

/** Title and body as one block so the pair stays tight while the steps breathe. */
@Composable
private fun OnboardingHeading(title: String, body: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        VmText(
            text = title,
            style = VmTheme.typography.headingLg,
            color = VmTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            VmText(
                text = body,
                style = VmTheme.typography.bodyLg,
                color = VmTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun CreateIdentityNameEntry(
    displayName: String,
    error: DisplayNameError?,
    onDisplayNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    OnboardingHeading(
        title = stringResource(R.string.create_identity_name_title),
        body = stringResource(R.string.create_identity_name_body),
    )
    // The one thing this step asks for, so the keyboard is up as it opens rather than a tap away.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    VmTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
        config = VmTextFieldConfig(
            label = stringResource(R.string.create_identity_name_label),
            isError = error != null,
            supportingText = error?.message(),
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onCreate() }),
    )
    VmButton(
        text = stringResource(R.string.create_identity_name_action),
        onClick = onCreate,
        modifier = Modifier.fillMaxWidth(),
        enabled = displayName.isNotBlank(),
    )
}

@Composable
internal fun CreateIdentityLoading() {
    VmProgressIndicator()
    VmText(
        text = stringResource(R.string.create_identity_creating),
        style = VmTheme.typography.bodyMd,
        color = VmTheme.colors.textSecondary,
    )
}

/**
 * The payoff. The identicon is the user's first sight of the identity they just generated, so it
 * leads — the hash underneath is the part they hand to other people.
 */
@Composable
internal fun CreateIdentitySuccess(
    identity: Identity,
    restored: RestoreSummary?,
    onContinue: () -> Unit,
) {
    val title = if (restored != null) {
        R.string.restore_backup_success_title
    } else {
        R.string.create_identity_success_title
    }
    Avatar(
        seed = identity.identityHash,
        name = identity.displayName,
        size = VmSizes.avatarLg,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xs),
    ) {
        VmText(
            text = stringResource(title),
            style = VmTheme.typography.headingLg,
            color = VmTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (identity.displayName.isNotBlank()) {
            VmText(
                text = identity.displayName,
                style = VmTheme.typography.bodyLgMedium,
                color = VmTheme.colors.textPrimary,
            )
        }
        if (restored != null) {
            VmText(
                text = restoreSummaryText(restored),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
    IdentityHashCard(userHash = identity.userHash)
    VmButton(
        text = stringResource(R.string.create_identity_continue),
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Counts are read by a Persian speaker, so they are converted before the template sees them. */
@Composable
private fun restoreSummaryText(summary: RestoreSummary): String = stringResource(
    R.string.restore_backup_summary,
    VmTextFormat.digits(summary.contacts.toString()),
    VmTextFormat.digits(summary.conversations.toString()),
    VmTextFormat.digits(summary.messages.toString()),
)

@Composable
internal fun CreateIdentityError(message: String, onRetry: () -> Unit) {
    VmText(
        text = message,
        modifier = Modifier.fillMaxWidth(),
        style = VmTheme.typography.bodyLg,
        color = VmTheme.colors.textCritical,
        textAlign = TextAlign.Center,
    )
    VmButton(text = stringResource(R.string.create_identity_retry), onClick = onRetry)
}

/** Resolved here, not in the ViewModel: the bounds are digits the user reads. */
@Composable
internal fun DisplayNameError.message(): String = when (this) {
    DisplayNameError.OutOfRange -> stringResource(
        R.string.create_identity_name_error_length,
        VmTextFormat.digits(CreateIdentityViewModel.DISPLAY_NAME_MIN.toString()),
        VmTextFormat.digits(CreateIdentityViewModel.DISPLAY_NAME_MAX.toString()),
    )
}
