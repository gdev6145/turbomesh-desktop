package com.turbomesh.desktop.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LockScreen(
    onUnlock: () -> Unit,
    storedPin: String,
) {
    val s = LocalStrings.current
    var enteredPin by remember { mutableStateOf("") }
    var wrongAttempts by remember { mutableStateOf(0) }
    var shakeKey by remember { mutableStateOf(0) }

    // Shake animation triggered by shakeKey incrementing
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey > 0) {
            repeat(4) {
                shakeOffset.animateTo(10f, tween(50))
                shakeOffset.animateTo(-10f, tween(50))
            }
            shakeOffset.animateTo(0f, tween(50))
        }
    }

    fun tryUnlock() {
        if (enteredPin == storedPin) {
            onUnlock()
        } else {
            wrongAttempts++
            enteredPin = ""
            shakeKey++
        }
    }

    fun appendDigit(ch: String) {
        if (enteredPin.length < 6) {
            enteredPin += ch
            if (enteredPin.length == 6) tryUnlock()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D1B2A), Color(0xFF1B2B3A), Color(0xFF0D1B2A))
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(x = shakeOffset.value.dp)
                .padding(32.dp)
        ) {
            // Icon
            Text("🔐", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                s.unlockApp,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(32.dp))

            // PIN dots
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { i ->
                    val filled = i < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (filled) Color(0xFF64B5F6)
                                else Color.White.copy(alpha = 0.3f)
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Error message
            if (shakeKey > 0 && wrongAttempts > 0) {
                Text(
                    "${s.wrongPin} • $wrongAttempts ${s.attemptsRemaining}",
                    color = Color(0xFFEF5350),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(20.dp))
            }
            Spacer(Modifier.height(8.dp))

            // Numpad
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "⌫"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { key ->
                            NumpadButton(label = key, enabled = key.isNotEmpty()) {
                                when (key) {
                                    "⌫" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                    "" -> {}
                                    else -> appendDigit(key)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumpadButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.Transparent,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** PIN setup dialog used from Settings */
@Composable
fun PinSetupDialog(
    existingPin: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.setPin) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin1, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin1 = it },
                    label = { Text(s.enterPin) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = pin2, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin2 = it },
                    label = { Text(s.confirmPin) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error.isNotBlank())
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin1.length < 4 -> error = "PIN must be at least 4 digits"
                    pin1 != pin2 -> error = s.pinsDoNotMatch
                    else -> onSave(pin1)
                }
            }) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}
