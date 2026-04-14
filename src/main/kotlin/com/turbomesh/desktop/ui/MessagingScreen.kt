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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(FlowPreview::class)
@Composable
fun MessagingScreen(repo: MeshRepository) {
    val messages by repo.messages.collectAsState()
    val nodes by repo.scanResults.collectAsState()
    val typingNodes by repo.typingNodes.collectAsState()
    val reactions by repo.messages.collectAsState()

    var selectedDestination by remember { mutableStateOf(MeshMessage.BROADCAST_DESTINATION) }
    var inputText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showPinned by remember { mutableStateOf(false) }
    var replyToMsg by remember { mutableStateOf<MeshMessage?>(null) }
    var editingMsg by remember { mutableStateOf<MeshMessage?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Send typing with debounce
    var lastTypingSent by remember { mutableStateOf(0L) }

    val visibleMessages = remember(messages, selectedDestination, searchQuery) {
        messages
            .filter { it.deletedAtMs == null }
            .filter { msg ->
                selectedDestination == MeshMessage.BROADCAST_DESTINATION ||
                    msg.sourceNodeId == selectedDestination ||
                    msg.destinationNodeId == selectedDestination ||
                    msg.type == MeshMessageType.BROADCAST
            }
            .filter { msg ->
                searchQuery.isBlank() || String(msg.payload).contains(searchQuery, ignoreCase = true)
            }
            .filter { it.type !in listOf(MeshMessageType.REACTION, MeshMessageType.TYPING, MeshMessageType.HEARTBEAT) }
    }

    val pinnedMessages = remember(messages) {
        messages.filter { it.isPinned && it.deletedAtMs == null }
    }

    // Reaction map: targetMsgId -> list of emoji strings
    val reactionMap = remember(messages) {
        messages
            .filter { it.type == MeshMessageType.REACTION }
            .groupBy { String(it.payload).substringBefore(":") }
            .mapValues { (_, msgs) -> msgs.map { String(it.payload).substringAfter(":") } }
    }

    // Who's typing in the current conversation
    val whoIsTyping = remember(typingNodes, selectedDestination) {
        typingNodes.keys.filter { it != repo.localNodeId }
            .map { repo.getNickname(it).ifBlank { it.take(8) } }
    }

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty())
            listState.animateScrollToItem(visibleMessages.size - 1)
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
    }

    fun exportChat() {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val fname = "turbomesh_export_${sdf.format(Date())}.txt"
        val file = File(System.getProperty("user.home"), fname)
        val sb = StringBuilder("TurboMesh Chat Export — ${Date()}\n${"=".repeat(60)}\n\n")
        visibleMessages.forEach { msg ->
            val sender = repo.getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId.take(8) }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))
            sb.appendLine("[$time] $sender: ${String(msg.payload)}")
        }
        file.writeText(sb.toString())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {

            // Destination picker
            val destinations = listOf(MeshMessage.BROADCAST_DESTINATION) + nodes.map { it.id }
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    val label = if (selectedDestination == MeshMessage.BROADCAST_DESTINATION) "Everyone"
                    else repo.getNickname(selectedDestination).ifBlank { selectedDestination.take(10) }
                    Text("To: $label")
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

            // Emergency button
            IconTextButton("🆘", "Emergency") {
                repo.sendMessage(
                    MeshMessage.BROADCAST_DESTINATION,
                    "⚠️ EMERGENCY from ${repo.localNodeId}",
                    MeshMessageType.EMERGENCY
                )
            }
            Spacer(Modifier.width(4.dp))

            // Search toggle
            IconTextButton(if (showSearch) "✕" else "🔍", if (showSearch) "Close" else "Search") {
                showSearch = !showSearch; if (!showSearch) searchQuery = ""
            }
            Spacer(Modifier.width(4.dp))

            // Pinned toggle
            if (pinnedMessages.isNotEmpty()) {
                Badge(contentColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.primary) {
                    Text("${pinnedMessages.size}")
                }
                IconTextButton("📌", "Pinned") { showPinned = !showPinned }
                Spacer(Modifier.width(4.dp))
            }

            // Export
            IconTextButton("💾", "Export") { exportChat() }
        }

        // Search bar
        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                placeholder = { Text("Search messages…") },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty())
                        TextButton(onClick = { searchQuery = "" }) { Text("Clear") }
                }
            )
        }

        // Pinned panel
        if (showPinned && pinnedMessages.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text("📌 Pinned Messages", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    pinnedMessages.take(5).forEach { msg ->
                        val sender = repo.getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId.take(8) }
                        Text("$sender: ${String(msg.payload).take(80)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        HorizontalDivider()

        // Message count / search result count
        if (searchQuery.isNotBlank()) {
            Text("${visibleMessages.size} result${if (visibleMessages.size != 1) "s" else ""} for \"$searchQuery\"",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (visibleMessages.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center) {
                        Text(
                            if (searchQuery.isNotBlank()) "No messages match \"$searchQuery\""
                            else "No messages yet — send something!",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(visibleMessages, key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg,
                    isMine = msg.sourceNodeId == repo.localNodeId,
                    senderName = repo.getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId.take(8) },
                    reactions = reactionMap[msg.id] ?: emptyList(),
                    onReply = { replyToMsg = msg },
                    onEdit = { editingMsg = msg; inputText = String(msg.payload) },
                    onDelete = { repo.deleteMessage(msg.id) },
                    onPin = { repo.pinMessage(msg.id, !msg.isPinned) },
                    onRead = { repo.markRead(msg.id) },
                    onReact = { emoji -> repo.sendReaction(msg.id, selectedDestination, emoji) },
                )
            }
        }

        // Typing indicator
        if (whoIsTyping.isNotEmpty()) {
            Text(
                "${whoIsTyping.joinToString(", ")} ${if (whoIsTyping.size == 1) "is" else "are"} typing…",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
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
                onValueChange = { v ->
                    inputText = v
                    val now = System.currentTimeMillis()
                    if (now - lastTypingSent > 2_000 && v.isNotBlank()
                        && selectedDestination != MeshMessage.BROADCAST_DESTINATION) {
                        repo.sendTyping(selectedDestination)
                        lastTypingSent = now
                    }
                },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && !event.isShiftPressed
                        && event.type == KeyEventType.KeyDown) {
                        sendOrEdit(); true
                    } else false
                },
                placeholder = { Text("Message… (Enter to send, Shift+Enter newline)") },
                maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { sendOrEdit() },
                enabled = inputText.isNotBlank(),
                modifier = Modifier.height(56.dp)
            ) { Text(if (editingMsg != null) "Update" else "Send") }
        }
    }
}

@Composable
private fun IconTextButton(icon: String, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
        Text(icon)
    }
}

@Composable
private fun MessageBubble(
    msg: MeshMessage,
    isMine: Boolean,
    senderName: String,
    reactions: List<String>,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onRead: () -> Unit,
    onReact: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    LaunchedEffect(msg.id) { if (!isMine && msg.readAtMs == null) onRead() }

    val isEmergency = msg.type == MeshMessageType.EMERGENCY
    val isBroadcast = msg.type == MeshMessageType.BROADCAST

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isMine) 16.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 16.dp
                    ))
                    .background(when {
                        isEmergency -> MaterialTheme.colorScheme.errorContainer
                        isBroadcast && !isMine -> MaterialTheme.colorScheme.tertiaryContainer
                        isMine -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    })
                    .padding(10.dp)
            ) {
                if (!isMine || isBroadcast) {
                    Text(senderName, style = MaterialTheme.typography.labelSmall,
                        color = if (isEmergency) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                }
                if (msg.isPinned) Text("📌 ", style = MaterialTheme.typography.labelSmall)
                if (isEmergency) Text("🆘 EMERGENCY", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)

                msg.replyToMsgId?.let {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp)) {
                        Text("↩ Reply", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(2.dp))
                }

                Text(String(msg.payload),
                    color = if (isEmergency) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurface)

                if (msg.isEdited) {
                    Text("(edited)", style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Reactions
                if (reactions.isNotEmpty()) {
                    val grouped = reactions.groupBy { it }.mapValues { it.value.size }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        grouped.forEach { (emoji, count) ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("$emoji $count",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(relativeTime(msg.timestamp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isMine) {
                        Text(if (msg.isAcknowledged) " ✓✓" else " ✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.isAcknowledged) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { showMenu = true },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(16.dp).widthIn(min = 24.dp)) {
                        Text("•••", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Reply") }, onClick = { onReply(); showMenu = false })
                DropdownMenuItem(text = { Text("React") }, onClick = { showEmojiPicker = true; showMenu = false })
                if (isMine) DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); showMenu = false })
                DropdownMenuItem(text = { Text(if (msg.isPinned) "Unpin" else "Pin") },
                    onClick = { onPin(); showMenu = false })
                if (isMine) DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { onDelete(); showMenu = false })
            }

            if (showEmojiPicker) {
                DropdownMenu(expanded = true, onDismissRequest = { showEmojiPicker = false }) {
                    val emojis = listOf("👍","❤️","😂","😮","😢","😡","🔥","✅")
                    Row(modifier = Modifier.padding(8.dp)) {
                        emojis.forEach { emoji ->
                            TextButton(onClick = { onReact(emoji); showEmojiPicker = false }) {
                                Text(emoji, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }
}
