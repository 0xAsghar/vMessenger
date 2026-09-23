package ir.vmessenger.feature.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ir.vmessenger.core.designsystem.component.UserHashLabel
import ir.vmessenger.core.designsystem.component.UserHashShareRow
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The hash, its label and the copy/share pair as one soft card.
 *
 * Onboarding and the identity screen both end on this, so they share it: the user should not
 * have to recognise two different presentations of the one string they hand to other people.
 */
@Composable
internal fun IdentityHashCard(
    userHash: String,
    modifier: Modifier = Modifier,
) {
    VmSurface(shape = VmShapes.card, color = VmTheme.colors.bgSubtle, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VmSpacing.xs),
        ) {
            UserHashLabel()
            UserHashText(text = userHash)
            UserHashShareRow(userHash = userHash)
        }
    }
}
