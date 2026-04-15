package com.turbomesh.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.turbomesh.desktop.data.MeshRepository
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Base64

@Composable
fun PairingDialog(
    repo: MeshRepository,
    targetNodeId: String,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    val localKeyB64 = remember {
        Base64.getEncoder().encodeToString(repo.publicKeyBytes)
    }

    val qrBitmap: ImageBitmap? = remember(localKeyB64) {
        generateQrBitmap(localKeyB64, 200)?.toComposeImageBitmap()
    }

    var peerKeyInput by remember { mutableStateOf("") }
    var derivedPin by remember { mutableStateOf<String?>(null) }
    var pairSuccess by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf("") }
    var copiedKey by remember { mutableStateOf(false) }

    fun computePin() {
        keyError = ""
        try {
            val bytes = Base64.getDecoder().decode(peerKeyInput.trim())
            repo.storePeerKey(targetNodeId, bytes)
            derivedPin = repo.getPairingPin(targetNodeId)
            if (derivedPin == null) keyError = "Could not derive PIN — check key format"
        } catch (e: Exception) {
            keyError = "Invalid key: ${e.message?.take(60)}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 480.dp),
        title = {
            Text(s.pairingTitle, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Step 1: My public key + QR
                SectionLabel("1", s.pairingStep1)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "QR Code",
                            modifier = Modifier.size(120.dp)
                        )
                    }
                    Column {
                        Text(
                            s.myPublicKey,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(
                                localKeyB64.take(48) + "…",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(localKeyB64))
                                copiedKey = true
                            },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(if (copiedKey) s.copied else s.copyKey,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                HorizontalDivider()

                // Step 2: Peer key input
                SectionLabel("2", s.pairingStep2)
                OutlinedTextField(
                    value = peerKeyInput,
                    onValueChange = { peerKeyInput = it; derivedPin = null; pairSuccess = false; keyError = "" },
                    label = { Text(s.peerPublicKey) },
                    placeholder = { Text(s.peerKeyPlaceholder) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    isError = keyError.isNotBlank(),
                )
                if (keyError.isNotBlank()) {
                    Text(keyError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { computePin() }, enabled = peerKeyInput.isNotBlank()) {
                    Text(s.derivePin)
                }

                // Step 3: PIN display
                if (derivedPin != null) {
                    HorizontalDivider()
                    SectionLabel("3", s.pairingStep3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.pairingPin, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                derivedPin!!.chunked(3).joinToString(" "),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 28.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                letterSpacing = 6.sp,
                            )
                        }
                    }
                    Text(s.pairingVerifyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (pairSuccess) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Text(s.pairingSuccess, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { pairSuccess = true },
                enabled = derivedPin != null && !pairSuccess
            ) { Text(s.pairingConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancel) }
        }
    )
}

@Composable
private fun SectionLabel(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(22.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    // Simple wrapper — Compose Desktop supports text selection in TextField; here we just show
    content()
}

private fun generateQrBitmap(content: String, size: Int): BufferedImage? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) for (y in 0 until size) {
            img.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
        }
        img
    } catch (_: Exception) { null }
}
