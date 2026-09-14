package ir.vmessenger.feature.identity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * Onboarding only ever moves forward, so every step enters from the same side. The timings match
 * `NavTransitions` — the shared-axis values the rest of the app navigates with — so a step change
 * and a screen change read as one system rather than two.
 */
private const val STEP_SLIDE_MS = 280
private const val STEP_FADE_MS = 180
private const val STEP_SLIDE_FRACTION = 6

/** Intro, name, done. The restore branch is not on this path and shows no dots. */
private const val ONBOARDING_STEPS = 3

@Composable
fun CreateIdentityRoute(
    onIdentityCreated: () -> Unit,
    viewModel: CreateIdentityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Insets are consumed by the content, not the Scaffold: `safeDrawingPadding` also keeps the
    // step clear of the keyboard, which the default scaffold insets do not.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding(),
        ) {
            AnimatedContent(
                targetState = uiState,
                transitionSpec = { stepTransition() },
                contentKey = { it.stepKey() },
                label = "onboarding-step",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { state ->
                OnboardingStep(state, viewModel, onIdentityCreated)
            }
            uiState.onboardingStep()?.let { step -> StepIndicator(step = step) }
        }
    }
}

/**
 * The frame every step is drawn in: one gutter, one rhythm, and room to scroll once the keyboard
 * takes half the screen. The steps themselves emit content and nothing else.
 */
@Composable
private fun OnboardingStep(
    state: CreateIdentityUiState,
    viewModel: CreateIdentityViewModel,
    onIdentityCreated: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VmSpacing.xl, vertical = VmSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xl, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            CreateIdentityUiState.Intro -> CreateIdentityIntro(
                onContinue = viewModel::onIntroContinue,
                onRestoreFile = viewModel::onBackupFileSelected,
            )
            is CreateIdentityUiState.NameEntry -> CreateIdentityNameEntry(
                displayName = state.displayName,
                error = state.error,
                onDisplayNameChange = viewModel::onDisplayNameChange,
                onCreate = viewModel::createIdentity,
            )
            CreateIdentityUiState.Creating -> CreateIdentityLoading()
            is CreateIdentityUiState.Success -> CreateIdentitySuccess(
                identity = state.identity,
                restored = state.restored,
                onContinue = onIdentityCreated,
            )
            is CreateIdentityUiState.Error -> CreateIdentityError(
                message = state.message,
                onRetry = viewModel::retryFromError,
            )
            CreateIdentityUiState.InspectingBackup -> RestoreProgressStep(inspecting = true)
            is CreateIdentityUiState.RestoreConfirm -> RestoreConfirmStep(
                state = state,
                onPassphraseChange = viewModel::onRestorePassphraseChange,
                onRestore = viewModel::restoreBackup,
                onCancel = viewModel::cancelRestore,
            )
            CreateIdentityUiState.Restoring -> RestoreProgressStep(inspecting = false)
            is CreateIdentityUiState.RestoreFailed -> RestoreFailedStep(
                failure = state.failure,
                onBack = viewModel::cancelRestore,
            )
        }
    }
}

private fun AnimatedContentTransitionScope<CreateIdentityUiState>.stepTransition(): ContentTransform =
    slideIntoContainer(
        towards = SlideDirection.Start,
        animationSpec = tween(STEP_SLIDE_MS),
        initialOffset = { it / STEP_SLIDE_FRACTION },
    ) + fadeIn(tween(STEP_FADE_MS)) togetherWith fadeOut(tween(STEP_FADE_MS))

/** Typing in the name field must not restart the transition, so the key is the step, not the state. */
private fun CreateIdentityUiState.stepKey(): Int = when (this) {
    CreateIdentityUiState.Intro -> 0
    is CreateIdentityUiState.NameEntry -> 1
    CreateIdentityUiState.Creating -> 2
    is CreateIdentityUiState.Success -> 3
    is CreateIdentityUiState.Error -> 4
    CreateIdentityUiState.InspectingBackup -> 5
    is CreateIdentityUiState.RestoreConfirm -> 6
    CreateIdentityUiState.Restoring -> 7
    is CreateIdentityUiState.RestoreFailed -> 8
}

/**
 * Where the user is on the straight path from intro to a working identity, or null where dots
 * would lie: a restore is a different shape, and an error is not progress.
 */
private fun CreateIdentityUiState.onboardingStep(): Int? = when (this) {
    CreateIdentityUiState.Intro -> 0
    is CreateIdentityUiState.NameEntry -> 1
    CreateIdentityUiState.Creating -> 1
    is CreateIdentityUiState.Success -> if (restored == null) 2 else null
    else -> null
}

@Composable
private fun StepIndicator(step: Int) {
    val position = stringResource(
        R.string.create_identity_step_indicator,
        VmTextFormat.persianDigits((step + 1).toString()),
        VmTextFormat.persianDigits(ONBOARDING_STEPS.toString()),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VmSpacing.xl)
            .clearAndSetSemantics { contentDescription = position },
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(ONBOARDING_STEPS) { index -> StepDot(active = index == step) }
    }
}

@Composable
private fun StepDot(active: Boolean) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(width = if (active) VmSpacing.lg else VmSpacing.sm, height = VmSpacing.sm)
            .clip(CircleShape)
            .background(color),
    )
}
