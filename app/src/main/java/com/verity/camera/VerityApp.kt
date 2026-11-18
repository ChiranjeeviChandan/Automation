package com.verity.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VerityApp(vm: VerityViewModel) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.reviewing) ReviewScreen(state, vm)
        else CameraScreen(onCaptured = vm::onCaptured, onPicked = vm::onPicked, onSettings = { vm.toggleSettings(true) })

        state.busy?.let { label ->
            Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(label, color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
    }
    if (state.showSettings) SettingsDialog(vm.settings) { vm.toggleSettings(false) }
}

@Composable
private fun SettingsDialog(settings: Settings, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(settings.apiKey) }
    var ai by remember { mutableStateOf(settings.aiEnabled) }
    var uhd by remember { mutableStateOf(settings.upscaleTo4k) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI scene direction (Claude)", Modifier.weight(1f)); Switch(ai, { ai = it })
                }
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, label = { Text("Anthropic API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text(
                    "With AI on, a 1024-px preview and image statistics are sent to Anthropic to choose the grade. " +
                        "Pixels are always processed on this phone. Without a key, an offline auto-grade is used.",
                    fontSize = 12.sp, color = Color(0xFF9E9E9E),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Upscale smaller photos to 4K", Modifier.weight(1f)); Switch(uhd, { uhd = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.apiKey = key; settings.aiEnabled = ai; settings.upscaleTo4k = uhd; onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
