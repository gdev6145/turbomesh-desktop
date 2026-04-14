package com.turbomesh.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.mesh.MeshMessage
import com.turbomesh.desktop.mesh.MeshMessageType
import kotlinx.coroutines.launch

@Composable
fun MessagingScreen(repo: MeshRepository) {
    val messages by repo.messages.collectAsState()
    val nodes by repo.scanResults.collectAsState()

    var selectedDestination by remember { mutableStateOf(MeshMessage.BROADCAST_DESTINATION) }
    var inputText by remember { mutableStateOf("") }
    var replyToMsg by remember { mutableStateOf<MeshMessage?>(null) }
    var editingMsg by remember { mutableStateOf<MeshMessage?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val visibleMessages = remember(messages, selectedDestination) {
        messages.filter { it.deletedAtMs == null }.filter { msg ->
            selectedDestination == MeshMessage.BROADCAST_DESTINATION ||
                msg.sourceNodeId == selectedDestination ||
                msg.destinationNodeId == selectedDestination ||
                msg.type == MeshMessageType.BROADCAST
        }
    }

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) listState.animateScrollToItem(visibleMessages.size - 1)
    }

    fun sendOrEdit() {
        if (inputText.isBlank()) return
        if (editingMsg != null) {
            repo.editMessage(editingMsg!!.id, inputText)
        } else if (selectedDestination == MeshMessage.BROADCAST_DESTINATION) {
            repo.broadcastMessage(inputText)
        } else {
            repo.sendMessage(selectedDestination, inputText, replyToMsgId = replyToMsg?.id)
        }
        inputText = ""; replyToMsg = null; editingMsg = null
        coroutineScope.launch { if (visibleMessages.isNotEmpty()) listState.animateScrollToItem(visibleMessages.size - 1) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Destination selector
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("To:", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))

            val destinations = listOf(MeshMessage.BROADCAST_DESTINATION) + nodes.map { it.id }
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    val label = if (selectedDestination == MeshMessage.BROADCAST_DESTINATION) "Everyone (Broadcast)"
                    else repo.getNickname(selectedDestination).ifBlank { selectedDestination }
                    Text(label)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    destinations.forEach { dest ->
                        DropdownMenuItem(
                            text = {
                                Text(if (dest == MeshMessage.BROADCAST_DESTINATION) "Everyone (Broadcast)"
                                else repo.getNickname(dest).ifBlank { dest })
                            },
                            onClick = { selectedDestination = dest; expanded = false }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Text("${visibleMessages.size} messages",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (visibleMessages.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center) {
                        Text("No messages yet — send something!",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(visibleMessages, key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg,
                    isMine = msg.sourceNodeId == repo.localNodeId,
                    senderName = repo.getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId.take(8) },
                    onReply = { replyToMsg = msg },
                    onEdit = { editingMsg = msg; inputText = String(msg.payload) },
                    onDelete = { repo.deleteMessage(msg.id) },
                    onPin = { repo.pinMessage(msg.id, !msg.isPinned) },
                    onRead = { repo.markRead(msg.id) },
                )
            }
        }

        // Reply/edit banner
        val banner = editingMsg ?: replyToMsg
        if (banner != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (editingMsg != null) "Editing message" else "Replying to:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(String(banner.payload).take(80),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    TextButton(onClick = { replyToMsg = null; editingMsg = null; inputText = "" }) {
                        Text("Cancel")
                    }
                }
            }
        }

        HorizontalDivider()

        // Input row
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && !event.isShiftPressed
                        && event.type == KeyEventType.KeyDown) {
                        sendOrEdit(); true
                    } else false
                },
                placeholder = { Text("Message… (Enter to send, Shift+Enter for newline)") },
                maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { sendOrEdit() },
                enabled = inputText.isNotBlank(),
                modifier = Modifier.height(56.dp)
            ) {
                Text(if (editingMsg != null) "Update" else "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: MeshMessage,
    isMine: Boolean,
    senderName: String,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onRead: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(msg.id) { if (!isMine && msg.readAtMs == null) onRead() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isMine) 16.dp else 4.dp,
                            bottomEnd = if (isMine) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isMine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(10.dp)
            ) {
                if (!isMine) {
                    Text(senderName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                }
                if (msg.isPinned) {
                    Text("📌", style = MaterialTheme.typography.labelSmall)
                }
                msg.replyToMsgId?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("↩ Reply", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(2.dp))
                }
                Text(String(msg.payload))
                if (msg.isEdited) {
                    Text("(edited)", style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatTime(msg.timestamp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isMine) {
                        Text(if (msg.isAcknowledged) " ✓✓" else " ✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.isAcknowledged) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = { showMenu = true },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(16.dp).widthIn(min = 24.dp)
                    ) {
                        Text("•••", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Reply") }, onClick = { onReply(); showMenu = false })
                if (isMine) DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); showMenu = false })
                DropdownMenuItem(
                    text = { Text(if (msg.isPinned) "Unpin" else "Pin") },
                    onClick = { onPin(); showMenu = false }
                )
                if (isMine) DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { onDelete(); showMenu = false }
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}
