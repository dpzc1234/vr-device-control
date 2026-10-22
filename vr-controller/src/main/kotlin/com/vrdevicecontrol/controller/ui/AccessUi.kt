package com.vrdevicecontrol.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrdevicecontrol.controller.access.AccessState
import com.vrdevicecontrol.controller.access.DeveloperAccess
import com.vrdevicecontrol.controller.access.UnlockResult
import com.vrdevicecontrol.controller.ui.components.toneColor
import com.vrdevicecontrol.controller.ui.theme.Tone

sealed interface AccessDialog {
    data object Unlock : AccessDialog
    data class SetPin(val changing: Boolean) : AccessDialog
    data object Recover : AccessDialog
    data class ShowRecovery(val code: String) : AccessDialog
}

/** Shown only in developer mode, so customers never see any hint of it. */
@Composable
fun DeveloperBar(access: AccessState, onSetPin: () -> Unit, onChangePin: () -> Unit, onLock: () -> Unit) {
    if (!access.developer) return
    val tone = if (access.pinSet) Tone.INFO else Tone.WARN
    val color = toneColor(tone)
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f)).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (access.pinSet) Icons.Rounded.Code else Icons.Rounded.WarningAmber, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (access.pinSet) "Developer mode · library editing enabled · locks after 15 min idle"
            else "Set a developer PIN before handing this tablet over. Until then, anyone can edit the library.",
            style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f),
        )
        if (access.pinSet) {
            TextButton(onClick = onChangePin) { Text("Change PIN") }
            TextButton(onClick = onLock) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Lock")
            }
        } else {
            Button(onClick = onSetPin) { Text("Set PIN") }
        }
    }
}

@Composable
fun AccessDialogs(dialog: AccessDialog?, vm: ControllerViewModel, onChange: (AccessDialog?) -> Unit) {
    when (dialog) {
        null -> Unit
        AccessDialog.Unlock -> UnlockDialog(vm, onDismiss = { onChange(null) }, onForgot = { onChange(AccessDialog.Recover) })
        is AccessDialog.SetPin -> SetPinDialog(dialog.changing, vm, onDismiss = { onChange(null) }) { onChange(AccessDialog.ShowRecovery(it)) }
        AccessDialog.Recover -> RecoverDialog(vm, onDismiss = { onChange(null) }) { onChange(AccessDialog.ShowRecovery(it)) }
        is AccessDialog.ShowRecovery -> RecoveryCodeDialog(dialog.code, onDone = { onChange(null) })
    }
}

private fun lockoutText(ms: Long) = "Too many attempts. Try again in ${(ms + 999) / 1000} s."

@Composable
private fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UnlockDialog(vm: ControllerViewModel, onDismiss: () -> Unit, onForgot: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Developer access") },
        text = {
            Column {
                PinField(pin, "PIN") { pin = it; error = null }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = toneColor(Tone.BAD), style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Forgot PIN?",
                    Modifier.clickable(onClick = onForgot).padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(enabled = pin.length >= 6 && !busy, onClick = {
                busy = true
                vm.unlock(pin) { result ->
                    busy = false
                    when (result) {
                        UnlockResult.Success -> onDismiss()
                        is UnlockResult.Wrong -> { pin = ""; error = "Wrong PIN." + if (result.attemptsBeforeLockout <= 2) " ${result.attemptsBeforeLockout} attempt(s) before a lockout." else "" }
                        is UnlockResult.LockedOut -> { pin = ""; error = lockoutText(result.remainingMs) }
                    }
                }
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SetPinDialog(changing: Boolean, vm: ControllerViewModel, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val mismatch = confirm.length >= pin.length && confirm.isNotEmpty() && confirm != pin
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changing) "Change developer PIN" else "Set developer PIN") },
        text = {
            Column {
                Text(
                    "6–8 digits. Customers will only be able to run sessions and manage headsets; adding or changing videos will need this PIN.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PinField(pin, "New PIN") { pin = it }
                Spacer(Modifier.height(8.dp))
                PinField(confirm, "Repeat PIN") { confirm = it }
                if (mismatch) Text("PINs don't match", color = toneColor(Tone.BAD), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = DeveloperAccess.isValidPin(pin) && pin == confirm && !busy, onClick = {
                busy = true
                vm.setPin(pin) { code -> busy = false; onSet(code) }
            }) { Text("Save PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecoverDialog(vm: ControllerViewModel, onDismiss: () -> Unit, onRecovered: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset PIN with recovery code") },
        text = {
            Column {
                Text("Enter the recovery code recorded when the PIN was set, then choose a new PIN.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    code, { code = it.uppercase().take(19); error = null },
                    label = { Text("Recovery code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                PinField(pin, "New PIN") { pin = it }
                Spacer(Modifier.height(8.dp))
                PinField(confirm, "Repeat new PIN") { confirm = it }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = toneColor(Tone.BAD), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = DeveloperAccess.normalize(code).length == 16 && DeveloperAccess.isValidPin(pin) && pin == confirm && !busy, onClick = {
                busy = true
                vm.recover(code, pin) { result, newCode ->
                    busy = false
                    when (result) {
                        UnlockResult.Success -> newCode?.let(onRecovered)
                        is UnlockResult.Wrong -> error = "That recovery code is not valid."
                        is UnlockResult.LockedOut -> error = lockoutText(result.remainingMs)
                    }
                }
            }) { Text("Reset PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecoveryCodeDialog(code: String, onDone: () -> Unit) {
    var recorded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {}, // Must be acknowledged: this code is shown only once.
        title = { Text("Record your recovery code") },
        text = {
            Column {
                Text(
                    "This code resets a forgotten PIN without losing data. It is shown only now and replaces any previous code. Keep it in your records, not on the tablet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.clickable { recorded = !recorded }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recorded, { recorded = it })
                    Text("I have recorded this code")
                }
            }
        },
        confirmButton = { Button(enabled = recorded, onClick = onDone) { Text("Done") } },
    )
}
