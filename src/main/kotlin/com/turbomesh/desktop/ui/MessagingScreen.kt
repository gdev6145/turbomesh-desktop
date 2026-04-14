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

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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

    Column(modifier = Modifier.fillMaxSize()) {
        // Destination selector
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("To: ", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            val destinations = listOf(MeshMessage.BROADCAST_DESTINATION) + nodes.map { it.id }
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    val nick = if (selectedDestination == MeshMessage.BROADCAST_DESTINATION) "Everyone (Broadcast)"
                    else repo.getNickname(selectedDestination).ifBlank { selectedDestination }
                    Text(nick)
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
        }
        Divider()

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(visibleMessages, key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg,
                    isMine = msg.sourceNodeId == repo.localNodeId,
                    senderName = repo.getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId },
                    onReply = { replyToMsg = msg },
                    onEdit = { editingMsg = msg; inputText = String(msg.payload) },
                    onDelete = { repo.deleteMessage(msg.id) },
                    onPin = { repo.pinMessage(msg.id, !msg.isPinned) },
                    onRead = { repo.markRead(msg.id) },
                )
            }
        }

        // Reply banner
        replyToMsg?.let { reply ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Replying to:", style = MaterialTheme.typography.labelSmall)
                        Text(String(reply.payload).take(60), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { replyToMsg = null }) { Text("×") }
                }
            }
        }

        // Input row
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f).onKeyEvent { event ->
                    if (event.key == Key.Enter && !event.isShiftPressed && event.type == KeyEventType.KeyDown) {
                        sendOrEdit(repo, inputText, selectedDestination, replyToMsg, editingMsg)
                        inputText = ""; replyToMsg = null; editingMsg = null; true
                    } else false
                },
                placeholder = { Text("Message… (Enter to send, Shift+Enter for newline)") },
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    sendOrEdit(repo, inputText, selectedDestination, replyToMsg, editingMsg)
                    inputText = ""; replyToMsg = null; editingMsg = null
                },
                enabled = inputText.isNotBlank()
            ) {
                Text(if (editingMsg != null) "Update" else "Send")
            }
        }
    }
}

private fun sendOrEdit(
    repo: MeshRepository,
    text: String,
    dest: String,
    replyTo: MeshMessage?,
    editing: MeshMessage?,
) {
    if (text.isBlank()) return
    if (editing != null) {
        repo.editMessage(editing.id, text)
    } else if (dest == MeshMessage.BROADCAST_DESTINATION) {
        repo.broadcastMessage(text)
    } else {
        repo.sendMessage(dest, text, replyToMsgId = replyTo?.id)
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
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isMine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(10.dp)
            ) {
                if (!isMine) {
                    Text(senderName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (msg.isPinned) {
                    Text("📌 Pinned", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                }
                msg.replyToMsgId?.let {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)) {
                        Text("↩ Reply", modifier = Modifier.padding(4.dp),
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
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start) {
                    Text(
                        formatTime(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMine && msg.isAcknowledged) {
                        Text(" ✓✓", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Reply") }, onClick = { onReply(); showMenu = false })
                if (isMine) DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); showMenu = false })
                DropdownMenuItem(text = { Text(if (msg.isPinned) "Unpin" else "Pin") },
                    onClick = { onPin(); showMenu = false })
                if (isMine) DropdownMenuItem(text = { Text("Delete") },
                    onClick = { onDelete(); showMenu = false })
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}
