package ir.vmessenger.feature.chat.group

import androidx.compose.runtime.Immutable
import ir.vmessenger.core.designsystem.component.UiMessage

/** The two halves of "new group": choose who is in it, then give it a name. */
enum class NewGroupStep { PickMembers, NameGroup }

/**
 * The whole "new group" flow.
 *
 * Naming comes second on purpose: the membership is the decision, the name is only a label,
 * and backing out of the name step keeps the selection rather than starting over.
 */
@Immutable
data class NewGroupUiState(
    val picker: GroupPickerState = GroupPickerState(),
    val step: NewGroupStep = NewGroupStep.PickMembers,
    val name: String = "",
    val creating: Boolean = false,
    val message: UiMessage? = null,
) {
    val nameValid: Boolean get() = GroupLimits.isValidName(name)

    /** The name step is only worth entering once somebody is actually in the group. */
    val canContinue: Boolean get() = picker.hasSelection && !creating

    val canCreate: Boolean get() = picker.hasSelection && nameValid && !creating

    /** Characters left, so the cap is visible next to the field instead of at the cut. */
    val nameRemaining: Int get() = GroupLimits.MAX_NAME_LENGTH - name.length
}
