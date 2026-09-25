package ir.vmessenger.feature.provision

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import ir.vmessenger.core.designsystem.component.VmCodeBlock
import ir.vmessenger.core.designsystem.component.VmNotice
import ir.vmessenger.core.designsystem.component.VmNoticeKind
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmSecretField
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.theme.VmSpacing

@Composable
internal fun IntroStep(onEvent: (NewNodeEvent) -> Unit) {
    Heading(stringResource(R.string.provision_intro_title))
    Body(stringResource(R.string.provision_intro_body))
    VmNotice(
        text = stringResource(R.string.provision_intro_needs),
        title = stringResource(R.string.provision_intro_needs_title),
    )
    Body(stringResource(R.string.provision_intro_offline))
    NavButtons(
        onNext = { onEvent(NewNodeEvent.Next) },
        onBack = null,
        nextLabel = stringResource(R.string.provision_begin),
    )
}

@Composable
internal fun ServerStep(form: NewNodeForm, viewModel: NewNodeViewModel) {
    val onEvent = viewModel::onEvent
    Body(stringResource(R.string.provision_server_body))
    ServerFields(form) { onEvent(NewNodeEvent.Edit(it)) }
    Choice(stringResource(R.string.provision_auth_password), form.auth == AuthKind.Password) {
        onEvent(NewNodeEvent.Edit(form.copy(auth = AuthKind.Password)))
    }
    Choice(stringResource(R.string.provision_auth_key), form.auth == AuthKind.Key) {
        onEvent(NewNodeEvent.Edit(form.copy(auth = AuthKind.Key)))
    }
    when (form.auth) {
        AuthKind.Password -> VmSecretField(
            state = viewModel.password,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.provision_password),
            isError = form.error == FormError.Secret,
            supportingText = stringResource(R.string.provision_secret_error).takeIf { form.error == FormError.Secret },
        )
        AuthKind.Key -> KeyFields(form, viewModel)
    }
    Body(stringResource(R.string.provision_secrets_note))
    NavButtons(onNext = { onEvent(NewNodeEvent.Next) }, onBack = { onEvent(NewNodeEvent.Back) })
}

@Composable
private fun ServerFields(form: NewNodeForm, onEdit: (NewNodeForm) -> Unit) = Ltr {
    Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.md)) {
        VmTextField(
            value = form.host,
            onValueChange = { onEdit(form.copy(host = it)) },
            modifier = Modifier.fillMaxWidth(),
            config = VmTextFieldConfig(
                label = stringResource(R.string.provision_host),
                placeholder = "203.0.113.10",
                isError = form.error == FormError.Host,
                supportingText = stringResource(R.string.provision_host_error).takeIf { form.error == FormError.Host },
                keyboardType = KeyboardType.Uri,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(VmSpacing.md)) {
            VmTextField(
                value = form.user,
                onValueChange = { onEdit(form.copy(user = it)) },
                modifier = Modifier.weight(2f),
                config = VmTextFieldConfig(
                    label = stringResource(R.string.provision_user),
                    isError = form.error == FormError.User,
                ),
            )
            VmTextField(
                value = form.port,
                onValueChange = { onEdit(form.copy(port = it)) },
                modifier = Modifier.weight(1f),
                config = VmTextFieldConfig(
                    label = stringResource(R.string.provision_port),
                    isError = form.error == FormError.Port,
                    keyboardType = KeyboardType.Number,
                ),
            )
        }
    }
}

@Composable
private fun KeyFields(form: NewNodeForm, viewModel: NewNodeViewModel) {
    val context = LocalContext.current
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = uri?.let { readKeyFile(context, it) }
        if (key != null) viewModel.onEvent(NewNodeEvent.KeyFile(key.first, key.second))
    }
    VmOutlinedButton(
        text = form.keyName ?: stringResource(R.string.provision_key_pick),
        onClick = { pickKey.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth(),
    )
    if (form.error == FormError.Key) Body(stringResource(R.string.provision_key_error), critical = true)
    VmSecretField(
        state = viewModel.passphrase,
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.provision_passphrase),
        supportingText = stringResource(R.string.provision_passphrase_hint),
    )
}

@Composable
internal fun AddressStep(form: NewNodeForm, onEvent: (NewNodeEvent) -> Unit) {
    val onEdit = { changed: NewNodeForm -> onEvent(NewNodeEvent.Edit(changed)) }
    Body(stringResource(R.string.provision_address_body))
    Choice(stringResource(R.string.provision_address_ip), form.address == AddressKind.Ip) {
        onEdit(form.copy(address = AddressKind.Ip))
    }
    Choice(stringResource(R.string.provision_address_domain), form.address == AddressKind.Domain) {
        onEdit(form.copy(address = AddressKind.Domain))
    }
    when (form.address) {
        AddressKind.Ip -> VmNotice(
            text = stringResource(R.string.provision_pinned_disclosure),
            kind = VmNoticeKind.Warning,
        )
        AddressKind.Domain -> DomainFields(form, onEdit)
    }
    NavButtons(onNext = { onEvent(NewNodeEvent.Next) }, onBack = { onEvent(NewNodeEvent.Back) })
}

@Composable
private fun DomainFields(form: NewNodeForm, onEdit: (NewNodeForm) -> Unit) {
    Ltr {
        Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.md)) {
            VmTextField(
                value = form.domain,
                onValueChange = { onEdit(form.copy(domain = it)) },
                modifier = Modifier.fillMaxWidth(),
                config = VmTextFieldConfig(
                    label = stringResource(R.string.provision_domain),
                    placeholder = "node.example.com",
                    isError = form.error == FormError.Domain,
                    keyboardType = KeyboardType.Uri,
                ),
            )
            VmTextField(
                value = form.email,
                onValueChange = { onEdit(form.copy(email = it)) },
                modifier = Modifier.fillMaxWidth(),
                config = VmTextFieldConfig(
                    label = stringResource(R.string.provision_email),
                    isError = form.error == FormError.Email,
                    keyboardType = KeyboardType.Email,
                ),
            )
        }
    }
    Body(stringResource(R.string.provision_domain_note))
}

@Composable
internal fun SecurityStep(form: NewNodeForm, onEvent: (NewNodeEvent) -> Unit) {
    val onEdit = { changed: NewNodeForm -> onEvent(NewNodeEvent.Edit(changed)) }
    Toggle(
        label = stringResource(R.string.provision_secure),
        supporting = stringResource(R.string.provision_secure_body),
        checked = form.secure,
        onChange = { onEdit(form.copy(secure = it, keyOnly = form.keyOnly && it)) },
    )
    val keyAuth = form.auth == AuthKind.Key
    Toggle(
        label = stringResource(R.string.provision_key_only),
        supporting = stringResource(
            if (keyAuth) R.string.provision_key_only_body else R.string.provision_key_only_needs_key,
        ),
        checked = form.keyOnly,
        enabled = keyAuth && form.secure,
        onChange = { onEdit(form.copy(keyOnly = it)) },
    )
    if (form.keyOnly) {
        VmNotice(text = stringResource(R.string.provision_key_only_warning), kind = VmNoticeKind.Warning)
    }
    Toggle(
        label = stringResource(R.string.provision_use_as_relay),
        supporting = stringResource(R.string.provision_use_as_relay_body),
        checked = form.useAsRelay,
        onChange = { onEdit(form.copy(useAsRelay = it)) },
    )
    NavButtons(onNext = { onEvent(NewNodeEvent.Next) }, onBack = { onEvent(NewNodeEvent.Back) })
}

@Composable
internal fun ReviewStep(form: NewNodeForm, onEvent: (NewNodeEvent) -> Unit) {
    val (host, user) = ServerInput.split(form.host, form.user)
    Heading(stringResource(R.string.provision_review_title))
    Ltr { VmCodeBlock(text = "$user@$host:${ServerInput.clean(form.port)}") }
    val lines = buildList {
        add(stringResource(R.string.provision_review_os))
        add(
            if (form.address == AddressKind.Domain) {
                stringResource(R.string.provision_review_domain, ServerInput.clean(form.domain))
            } else {
                stringResource(R.string.provision_review_ip)
            },
        )
        if (form.secure) add(stringResource(R.string.provision_review_secure))
        if (form.keyOnly) add(stringResource(R.string.provision_review_key_only))
        add(
            stringResource(
                if (form.useAsRelay) R.string.provision_review_relay else R.string.provision_review_bootstrap,
            ),
        )
    }
    lines.forEach { Body("• $it") }
    Body(stringResource(R.string.provision_review_time))
    NavButtons(
        onNext = { onEvent(NewNodeEvent.Start) },
        onBack = { onEvent(NewNodeEvent.Back) },
        nextLabel = stringResource(
            if (form.updating != null) R.string.provision_update_start else R.string.provision_start,
        ),
    )
}
