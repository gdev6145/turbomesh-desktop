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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.turbomesh.desktop.data.MeshGroup
import com.turbomesh.desktop.data.MeshRepository
import com.turbomesh.desktop.mesh.MeshMessage
import com.turbomesh.desktop.mesh.MeshMessageType
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser

@OptIn(FlowPreview::class)
@Composable
fun MessagingScreen(repo: MeshRepository) {
    val s = LocalStrings.current
    val messages by repo.messages.collectAsState()
    val nodes by repo.scanResults.collectAsState()
    val typingNodes by repo.typingNodes.collectAsState()
    val groups by repo.groupStore.groups.collectAsState()
    val readReceipts by repo.readReceipts.collectAsState()
    val starred by repo.starStore.starred.collectAsState()
    val settings by repo.settingsStore.settings.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    var selectedDestination by remember { mutableStateOf(MeshMessage.BROADCAST_DESTINATION) }

    // Per-destination draft storage
    val drafts = remember { mutableStateMapOf<String, String>() }
    var inputText by remember { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showPinned by remember { mutableStateOf(false) }
    var replyToMsg by remember { mutableStateOf<MeshMessage?>(null) }
    var editingMsg by remember { mutableStateOf<MeshMessage?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showExpiryMenu by remember { mutableStateOf(false) }
    var selectedExpiryMs by remember { mutableStateOf<Long?>(null) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showStarred by remember { mutableStateOf(false) }
    var prevMessageCount by remember { mutableStateOf(0) }
    var forwardMsg by remember { mutableStateOf<MeshMessage?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    // Track last auto-replied messageId to avoid re-triggering
    val autoRepliedIds = remember { mutableSetOf<String>() }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var lastTypingSent by remember { mutableStateOf(0L) }

    // Save/restore draft when destination changes
    val prevDest = remember { mutableStateOf(selectedDestination) }
    LaunchedEffect(selectedDestination) {
        if (prevDest.value != selectedDestination) {
            drafts[prevDest.value] = inputText
            inputText = drafts[selectedDestination] ?: ""
            prevDest.value = selectedDestination
            replyToMsg = null
            editingMsg = null
        }
    }

    // Listen for incoming group invites → auto-accept
    LaunchedEffect(Unit) {
        repo.groupInvites.collect { (groupId, groupName, members) ->
            repo.groupStore.addFromInvite(groupId, groupName, members)
            snackbarHostState.showSnackbar("Joined group: $groupName")
        }
    }

    // Show snackbar when file received
    LaunchedEffect(Unit) {
        repo.receivedFiles.collect { (name, file) ->
            snackbarHostState.showSnackbar(
                message = "${s.fileReceived}: $name",
                actionLabel = s.showInFolder,
                duration = SnackbarDuration.Long,
            ).let { result ->
                if (result == SnackbarResult.ActionPerformed)
                    runCatching { Desktop.getDesktop().open(file.parentFile) }
            }
        }
    }

    // Sound notification on new inbound message
    val isMuted = remember(selectedDestination, settings.mutedDestinations) {
        settings.mutedDestinations.split(",").contains(selectedDestination)
    }
    LaunchedEffect(messages.size) {
        val newCount = messages.count { msg ->
            msg.readAtMs == null && msg.deletedAtMs == null &&
            msg.sourceNodeId != repo.localNodeId
        }
        if (newCount > prevMessageCount && settings.soundEnabled && !isMuted) {
            java.awt.Toolkit.getDefaultToolkit().beep()
        }
        prevMessageCount = newCount
    }

    // Auto-reply when Away/DND
    LaunchedEffect(messages.size) {
        if (!settings.autoReplyEnabled) return@LaunchedEffect
        if (settings.userStatus !in listOf("away", "dnd")) return@LaunchedEffect
        messages
            .filter { msg ->
                msg.id !in autoRepliedIds &&
                msg.sourceNodeId != repo.localNodeId &&
                msg.type == MeshMessageType.DATA &&
                msg.deletedAtMs == null
            }
            .forEach { msg ->
                autoRepliedIds.add(msg.id)
                repo.maybeAutoReply(msg.sourceNodeId)
            }
    }

    val isGroupDest = selectedDestination.startsWith("GRP_")
    val selectedGroup: MeshGroup? = if (isGroupDest) groups.firstOrNull { it.id == selectedDestination } else null

    val visibleMessages = remember(messages, selectedDestination, searchQuery) {
        messages
            .filter { it.deletedAtMs == null }
            .filter { msg ->
                when {
                    selectedDestination == MeshMessage.BROADCAST_DESTINATION ->
                        msg.type == MeshMessageType.BROADCAST ||
                        msg.destinationNodeId == MeshMessage.BROADCAST_DESTINATION
                    isGroupDest ->
                        msg.destinationNodeId == selectedDestination
                    else ->
                        msg.sourceNodeId == selectedDestination ||
                        msg.destinationNodeId == selectedDestination
                }
            }
            .filter { msg ->
                searchQuery.isBlank() || String(msg.payload).contains(searchQuery, ignoreCase = true)
            }
            .filter { msg ->
                msg.type !in listOf(
                    MeshMessageType.REACTION, MeshMessageType.TYPING, MeshMessageType.HEARTBEAT,
                    MeshMessageType.FILE_CHUNK, MeshMessageType.ACK, MeshMessageType.KEY_EXCHANGE,
                    MeshMessageType.READ, MeshMessageType.GROUP_INVITE,
                )
            }
    }

    val pinnedMessages = remember(messages) {
        messages.filter { it.isPinned && it.deletedAtMs == null }
    }

    val starredMessages = remember(messages, starred) {
        messages.filter { it.id in starred && it.deletedAtMs == null }
    }

    val reactionMap = remember(messages) {
        messages
            .filter { it.type == MeshMessageType.REACTION }
            .groupBy { String(it.payload).substringBefore(":") }
            .mapValues { (_, msgs) -> msgs.map { String(it.payload).substringAfter(":") } }
    }

    val whoIsTyping = remember(typingNodes, selectedDestination) {
        typingNodes.keys
            .filter { it != repo.localNodeId }
            .map { repo.getNickname(it).ifBlank { it.take(8) } }
    }

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty())
            listState.animateScrollToItem(visibleMessages.size - 1)
    }

    fun sendOrEdit() {
        if (inputText.isBlank()) return
        val expiresAtMs = selectedExpiryMs?.let { System.currentTimeMillis() + it }
        when {
            editingMsg != null -> repo.editMessage(editingMsg!!.id, inputText)
            isGroupDest -> repo.sendToGroup(selectedDestination, inputText)
            selectedDestination == MeshMessage.BROADCAST_DESTINATION -> repo.broadcastMessage(inputText)
            else -> repo.sendMessage(
                selectedDestination, inputText,
                replyToMsgId = replyToMsg?.id,
                expiresAtMs = expiresAtMs,
            )
        }
        inputText = ""
        drafts.remove(selectedDestination)
        replyToMsg = null
        editingMsg = null
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
        coroutineScope.launch {
            snackbarHostState.showSnackbar("${s.exportedTo}: ${file.absolutePath}", duration = SnackbarDuration.Long)
        }
    }

    // Dialogs
    if (showScheduleDialog) {
        ScheduleDialog(
            s = s,
            onDismiss = { showScheduleDialog = false },
            onSchedule = { scheduledMs ->
                if (inputText.isNotBlank()) {
                    repo.sendMessage(
                        selectedDestination, inputText,
                        scheduledAtMs = scheduledMs,
                        expiresAtMs = selectedExpiryMs?.let { System.currentTimeMillis() + it },
                    )
                    inputText = ""; drafts.remove(selectedDestination)
                    coroutineScope.launch {
                        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                        snackbarHostState.showSnackbar("${s.scheduledFor}: ${fmt.format(Date(scheduledMs))}")
                    }
                }
                showScheduleDialog = false
            }
        )
    }

    if (showForwardDialog && forwardMsg != null) {
        val msg = forwardMsg!!
        val destinations = buildList {
            add(MeshMessage.BROADCAST_DESTINATION)
            addAll(nodes.map { it.id })
            addAll(groups.map { it.id })
        }
        var fwdDest by remember { mutableStateOf(MeshMessage.BROADCAST_DESTINATION) }
        var fwdExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showForwardDialog = false; forwardMsg = null },
            title = { Text(s.forwardTo) },
            text = {
                Box {
                    OutlinedButton(onClick = { fwdExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        val label = when {
                            fwdDest == MeshMessage.BROADCAST_DESTINATION -> s.everyone
                            fwdDest.startsWith("GRP_") -> groups.firstOrNull { it.id == fwdDest }?.name ?: fwdDest
                            else -> repo.getNickname(fwdDest).ifBlank { fwdDest.take(12) }
                        }
                        Text(label)
                    }
                    DropdownMenu(expanded = fwdExpanded, onDismissRequest = { fwdExpanded = false }) {
                        destinations.forEach { dest ->
                            val label = when {
                                dest == MeshMessage.BROADCAST_DESTINATION -> s.everyoneBroadcast
                                dest.startsWith("GRP_") -> "# ${groups.firstOrNull { it.id == dest }?.name ?: dest}"
                                else -> repo.getNickname(dest).ifBlank { dest.take(12) }
                            }
                            DropdownMenuItem(text = { Text(label) }, onClick = { fwdDest = dest; fwdExpanded = false })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.forwardMessage(msg, fwdDest)
                    showForwardDialog = false; forwardMsg = null
                    coroutineScope.launch { snackbarHostState.showSnackbar(s.forwarded) }
                }) { Text(s.forward) }
            },
            dismissButton = { TextButton(onClick = { showForwardDialog = false; forwardMsg = null }) { Text(s.cancel) } }
        )
    }

    if (showNewGroupDialog) {
        NewGroupDialog(
            s = s,
            nodes = nodes.map { it.id to (repo.getNickname(it.id).ifBlank { it.id.take(10) }) },
            onCreate = { name, members ->
                repo.createGroup(name, members)
                coroutineScope.launch { snackbarHostState.showSnackbar(s.groupInviteSent) }
                showNewGroupDialog = false
            },
            onDismiss = { showNewGroupDialog = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = { paddingValues ->
            Row(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // ── Conversation Sidebar ──────────────────────────────────────
            ConversationSidebar(
                repo = repo,
                messages = messages,
                nodes = nodes,
                groups = groups,
                nodeLastSeen = repo.networkManager.nodeLastSeen.collectAsState().value,
                selectedDestination = selectedDestination,
                onSelect = { selectedDestination = it },
                s = s,
            )
            VerticalDivider()
            Column(modifier = Modifier.fillMaxSize()) {

                // Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Destination picker
                    val destinations = buildList {
                        add(MeshMessage.BROADCAST_DESTINATION)
                        addAll(nodes.map { it.id })
                        addAll(groups.map { it.id })
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            val label = when {
                                selectedDestination == MeshMessage.BROADCAST_DESTINATION -> s.everyone
                                isGroupDest -> selectedGroup?.name ?: selectedDestination
                                else -> repo.getNickname(selectedDestination).ifBlank { selectedDestination.take(10) }
                            }
                            val prefix = if (isGroupDest) "# " else "${s.toLabel} "
                            Text("$prefix$label")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text(s.everyoneBroadcast) },
                                onClick = { selectedDestination = MeshMessage.BROADCAST_DESTINATION; expanded = false }
                            )
                            if (nodes.isNotEmpty()) {
                                HorizontalDivider()
                                nodes.forEach { node ->
                                    DropdownMenuItem(
                                        text = { Text(repo.getNickname(node.id).ifBlank { node.id.take(12) }) },
                                        onClick = { selectedDestination = node.id; expanded = false }
                                    )
                                }
                            }
                            if (groups.isNotEmpty()) {
                                HorizontalDivider()
                                groups.forEach { group ->
                                    DropdownMenuItem(
                                        text = { Text("# ${group.name} (${group.members.size})") },
                                        onClick = { selectedDestination = group.id; expanded = false }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))

                    // New Group
                    IconTextButton("👥", s.newGroup) { showNewGroupDialog = true }
                    Spacer(Modifier.width(4.dp))

                    // Emergency
                    IconTextButton("🆘", s.emergency) {
                        repo.sendMessage(
                            MeshMessage.BROADCAST_DESTINATION,
                            "EMERGENCY from ${repo.localNodeId}",
                            MeshMessageType.EMERGENCY,
                        )
                    }
                    Spacer(Modifier.width(4.dp))

                    // Attach File
                    IconTextButton("📎", s.fileAttachLabel) {
                        val chooser = JFileChooser()
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val file = chooser.selectedFile
                            repo.sendFile(selectedDestination, file)
                            coroutineScope.launch { snackbarHostState.showSnackbar("Sending: ${file.name}") }
                        }
                    }
                    Spacer(Modifier.width(4.dp))

                    // Share Clipboard
                    IconTextButton("📋", s.shareClipboard) {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) {
                            repo.sendClipboard(selectedDestination, clipText)
                            coroutineScope.launch { snackbarHostState.showSnackbar(s.clipboardSent) }
                        }
                    }
                    Spacer(Modifier.width(4.dp))

                    // Mark all read
                    IconTextButton("✔️", s.markAllRead) {
                        repo.markAllRead(selectedDestination)
                    }
                    Spacer(Modifier.width(4.dp))

                    // Mute toggle
                    IconTextButton(
                        if (isMuted) "🔇" else "🔔",
                        if (isMuted) s.unmuteConversation else s.muteConversation
                    ) { repo.toggleMute(selectedDestination) }
                    Spacer(Modifier.width(4.dp))

                    // Starred toggle
                    if (starredMessages.isNotEmpty()) {
                        IconTextButton("⭐", s.starred) { showStarred = !showStarred }
                        Spacer(Modifier.width(4.dp))
                    }

                    // Clear conversation
                    if (selectedDestination != MeshMessage.BROADCAST_DESTINATION) {
                        IconTextButton("🗑", s.clearConversation) {
                            repo.clearConversation(selectedDestination)
                            coroutineScope.launch { snackbarHostState.showSnackbar(s.conversationCleared) }
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    // Search toggle
                    IconTextButton(if (showSearch) "✕" else "🔍", if (showSearch) s.close else s.search) {
                        showSearch = !showSearch; if (!showSearch) searchQuery = ""
                    }
                    Spacer(Modifier.width(4.dp))

                    // Pinned toggle
                    if (pinnedMessages.isNotEmpty()) {
                        Badge(contentColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.primary) {
                            Text("${pinnedMessages.size}")
                        }
                        IconTextButton("📌", s.pinned) { showPinned = !showPinned }
                        Spacer(Modifier.width(4.dp))
                    }

                    // Export
                    IconTextButton("💾", s.export) { exportChat() }
                }

                // Search bar
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        placeholder = { Text(s.searchMessages) },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty())
                                TextButton(onClick = { searchQuery = "" }) { Text(s.clearLabel) }
                        }
                    )
                }

                // Group info bar
                if (isGroupDest && selectedGroup != null) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("# ${selectedGroup.name}", fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f))
                            Text("${selectedGroup.members.size} members",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                repo.groupStore.deleteGroup(selectedGroup.id)
                                selectedDestination = MeshMessage.BROADCAST_DESTINATION
                            }) { Text(s.deleteGroup, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }

                // Starred panel
                if (showStarred) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(s.starredMessages, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            if (starredMessages.isEmpty()) {
                                Text(s.noStarredMessages, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                            } else {
                                starredMessages.take(5).forEach { msg ->
                                    val sender = repo.getNickname(msg.sourceNodeId).ifBlank { msg.sourceNodeId.take(8) }
                                    Text("⭐ $sender: ${String(msg.payload).take(80)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Pinned panel
                if (showPinned && pinnedMessages.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(s.pinnedMessages, style = MaterialTheme.typography.labelMedium,
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

                if (searchQuery.isNotBlank()) {
                    Text("${visibleMessages.size} ${if (visibleMessages.size != 1) s.results else s.result} ${s.forText} \"$searchQuery\"",
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
                                    if (searchQuery.isNotBlank()) "${s.noMessagesMatch} \"$searchQuery\""
                                    else s.noMessages,
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
                            readCount = readReceipts[msg.id]?.size ?: 0,
                            isStarred = msg.id in starred,
                            onReply = { replyToMsg = msg },
                            onEdit = { editingMsg = msg; inputText = String(msg.payload) },
                            onDelete = { repo.deleteMessage(msg.id) },
                            onPin = { repo.pinMessage(msg.id, !msg.isPinned) },
                            onStar = { repo.starStore.toggle(msg.id) },
                            onForward = { forwardMsg = msg; showForwardDialog = true },
                            onRead = { repo.markRead(msg.id) },
                            onReact = { emoji -> repo.sendReaction(msg.id, selectedDestination, emoji) },
                            onCopyClipboard = { text -> clipboardManager.setText(AnnotatedString(text)) },
                            onOpenFolder = { path -> runCatching { Desktop.getDesktop().open(File(path).parentFile) } },
                            s = s,
                        )
                    }
                }

                // Typing indicator
                if (whoIsTyping.isNotEmpty()) {
                    Text(
                        "${whoIsTyping.joinToString(", ")} ${if (whoIsTyping.size == 1) s.isTyping else s.areTyping}",
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
                                Text(if (editingMsg != null) s.editingMessage else s.replyingTo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(String(banner.payload).take(80),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            TextButton(onClick = { replyToMsg = null; editingMsg = null; inputText = "" }) {
                                Text(s.cancel)
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Expiry indicator
                if (selectedExpiryMs != null) {
                    val expiryLabel = when (selectedExpiryMs) {
                        3_600_000L -> s.expiry1h
                        86_400_000L -> s.expiry24h
                        604_800_000L -> s.expiry7d
                        else -> ""
                    }
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("⏱ ${s.messageExpiry}: $expiryLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { selectedExpiryMs = null }) { Text("✕") }
                        }
                    }
                }

                // Quick reply chips
                val quickReplies = remember(settings.quickReplies) {
                    settings.quickReplies.split("|").filter { it.isNotBlank() }
                }
                if (quickReplies.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(quickReplies.size) { i ->
                            SuggestionChip(
                                onClick = {
                                    if (isGroupDest) repo.sendToGroup(selectedDestination, quickReplies[i])
                                    else repo.sendMessage(selectedDestination, quickReplies[i])
                                },
                                label = { Text(quickReplies[i], style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }

                // Input row
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom) {

                    // Expiry picker
                    Box {
                        TextButton(
                            onClick = { showExpiryMenu = true },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.height(48.dp),
                        ) { Text("⏱", style = MaterialTheme.typography.titleMedium) }
                        DropdownMenu(expanded = showExpiryMenu, onDismissRequest = { showExpiryMenu = false }) {
                            DropdownMenuItem(text = { Text(s.noExpiry) }, onClick = { selectedExpiryMs = null; showExpiryMenu = false })
                            DropdownMenuItem(text = { Text(s.expiry1h) }, onClick = { selectedExpiryMs = 3_600_000L; showExpiryMenu = false })
                            DropdownMenuItem(text = { Text(s.expiry24h) }, onClick = { selectedExpiryMs = 86_400_000L; showExpiryMenu = false })
                            DropdownMenuItem(text = { Text(s.expiry7d) }, onClick = { selectedExpiryMs = 604_800_000L; showExpiryMenu = false })
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { v ->
                            inputText = v
                            val now = System.currentTimeMillis()
                            if (now - lastTypingSent > 2_000 && v.isNotBlank()
                                && selectedDestination != MeshMessage.BROADCAST_DESTINATION
                                && !isGroupDest) {
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
                        placeholder = { Text(s.messagePlaceholder) },
                        maxLines = 6,
                    )
                    Spacer(Modifier.width(4.dp))

                    // Schedule button
                    TextButton(
                        onClick = { showScheduleDialog = true },
                        modifier = Modifier.height(56.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        enabled = inputText.isNotBlank(),
                    ) { Text("⏰") }

                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { sendOrEdit() },
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier.height(56.dp)
                    ) { Text(if (editingMsg != null) s.update else s.send) }
                }
            } // end inner Column
            } // end outer Row
        }
    )
}

@Composable
private fun ConversationSidebar(
    repo: MeshRepository,
    messages: List<MeshMessage>,
    nodes: List<com.turbomesh.desktop.mesh.MeshNode>,
    groups: List<MeshGroup>,
    nodeLastSeen: Map<String, Long>,
    selectedDestination: String,
    onSelect: (String) -> Unit,
    s: AppStrings,
) {
    // Build conversation list: broadcast + each node + each group
    val destinations: List<String> = remember(nodes, groups) {
        buildList {
            add(MeshMessage.BROADCAST_DESTINATION)
            addAll(nodes.map { it.id })
            addAll(groups.map { it.id })
        }
    }

    // Unread count per destination
    val unreadPerDest = remember(messages) {
        messages.filter { it.readAtMs == null && it.deletedAtMs == null && it.sourceNodeId != repo.localNodeId }
            .groupBy { msg ->
                // Group messages belong to the group destination
                when {
                    msg.type == MeshMessageType.BROADCAST -> MeshMessage.BROADCAST_DESTINATION
                    groups.any { g -> g.id == msg.destinationNodeId } -> msg.destinationNodeId
                    else -> msg.sourceNodeId
                }
            }.mapValues { it.value.size }
    }

    // Last message snippet per destination
    val lastMsgPerDest = remember(messages) {
        messages.filter { it.deletedAtMs == null &&
                it.type !in listOf(MeshMessageType.REACTION, MeshMessageType.TYPING,
                    MeshMessageType.HEARTBEAT, MeshMessageType.FILE_CHUNK,
                    MeshMessageType.ACK, MeshMessageType.KEY_EXCHANGE,
                    MeshMessageType.READ, MeshMessageType.GROUP_INVITE) }
            .groupBy { msg ->
                when {
                    msg.type == MeshMessageType.BROADCAST -> MeshMessage.BROADCAST_DESTINATION
                    groups.any { g -> g.id == msg.destinationNodeId } -> msg.destinationNodeId
                    msg.sourceNodeId == repo.localNodeId -> msg.destinationNodeId
                    else -> msg.sourceNodeId
                }
            }.mapValues { (_, msgs) -> msgs.maxByOrNull { it.timestamp } }
    }

    Column(modifier = Modifier.width(200.dp).fillMaxHeight()) {
        Text(
            s.conversations,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(destinations) { dest ->
                val isSelected = dest == selectedDestination
                val unread = unreadPerDest[dest] ?: 0
                val lastMsg = lastMsgPerDest[dest]
                val isGroup = dest.startsWith("GRP_")
                val displayName = when {
                    dest == MeshMessage.BROADCAST_DESTINATION -> s.everyone
                    isGroup -> "# ${groups.firstOrNull { it.id == dest }?.name ?: dest}"
                    else -> repo.getNickname(dest).ifBlank { dest.take(10) }
                }
                val lastSeenMs = if (!isGroup && dest != MeshMessage.BROADCAST_DESTINATION)
                    nodeLastSeen[dest] else null
                val now = System.currentTimeMillis()
                val dotColor = when {
                    lastSeenMs == null -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                    now - lastSeenMs < 30_000 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                    now - lastSeenMs < 300_000 -> androidx.compose.ui.graphics.Color(0xFFFFCA28)
                    else -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                }

                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(dest) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Presence dot (not for broadcast/groups)
                        if (!isGroup && dest != MeshMessage.BROADCAST_DESTINATION) {
                            Box(Modifier.size(7.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(dotColor))
                            Spacer(Modifier.width(5.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (lastMsg != null) {
                                val snippet = String(lastMsg.payload).take(30)
                                Text(
                                    snippet,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (unread > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ) { Text("$unread") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleDialog(
    s: AppStrings,
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit,
) {
    var dateText by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.scheduleAt) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dateText, onValueChange = { dateText = it; error = "" },
                    label = { Text(s.datePlaceholder) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeText, onValueChange = { timeText = it; error = "" },
                    label = { Text(s.timePlaceholder) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotBlank())
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val dt = LocalDateTime.parse("${dateText.trim()} ${timeText.trim()}", fmt)
                    val ms = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (ms <= System.currentTimeMillis()) {
                        error = "Must be in the future"
                    } else {
                        onSchedule(ms)
                    }
                } catch (_: Exception) {
                    error = s.invalidDateTime
                }
            }) { Text(s.confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

@Composable
private fun NewGroupDialog(
    s: AppStrings,
    nodes: List<Pair<String, String>>,
    onCreate: (String, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    val selectedMembers = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.newGroup) },
        modifier = Modifier.widthIn(min = 380.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = groupName, onValueChange = { groupName = it; error = "" },
                    label = { Text(s.groupName) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(s.groupMembers, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
                if (nodes.isEmpty()) {
                    Text("No nodes discovered — scan in Devices tab first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    nodes.forEach { (id, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = id in selectedMembers,
                                onCheckedChange = { checked ->
                                    if (checked) { if (id !in selectedMembers) selectedMembers.add(id) }
                                    else selectedMembers.remove(id)
                                }
                            )
                            Text(name)
                        }
                    }
                }
                if (error.isNotBlank())
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    groupName.isBlank() -> error = "Group name required"
                    selectedMembers.isEmpty() -> error = "Select at least one member"
                    else -> onCreate(groupName.trim(), selectedMembers.toSet())
                }
            }) { Text(s.createGroup) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

@Composable
private fun IconTextButton(@Suppress("UNUSED_PARAMETER") icon: String, label: String, onClick: () -> Unit) {
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
    readCount: Int,
    isStarred: Boolean,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onStar: () -> Unit,
    onForward: () -> Unit,
    onRead: () -> Unit,
    onReact: (String) -> Unit,
    onCopyClipboard: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    s: AppStrings,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    LaunchedEffect(msg.id) { if (!isMine && msg.readAtMs == null) onRead() }

    val isEmergency = msg.type == MeshMessageType.EMERGENCY
    val isBroadcast = msg.type == MeshMessageType.BROADCAST
    val isFileComplete = msg.type == MeshMessageType.FILE_COMPLETE
    val isClipboard = msg.type == MeshMessageType.CLIPBOARD
    val isScheduled = msg.scheduledAtMs != null && msg.scheduledAtMs > System.currentTimeMillis()

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
                        isScheduled -> MaterialTheme.colorScheme.surfaceVariant
                        isEmergency -> MaterialTheme.colorScheme.errorContainer
                        isBroadcast && !isMine -> MaterialTheme.colorScheme.tertiaryContainer
                        isFileComplete || isClipboard -> MaterialTheme.colorScheme.secondaryContainer
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
                if (isStarred) Text("⭐ ", style = MaterialTheme.typography.labelSmall)
                if (isScheduled) Text("⏰ ${s.pendingScheduled}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                if (isEmergency) Text("🆘 EMERGENCY", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)

                msg.replyToMsgId?.let {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp)) {
                        Text("↩ ${s.reply}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(2.dp))
                }

                when {
                    isFileComplete -> {
                        val parts = String(msg.payload).split(":", limit = 3)
                        val filePath = if (parts.size >= 2) parts[1] else ""
                        val fileName = if (parts.size >= 3) parts[2] else "unknown"
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📎")
                            Column {
                                Text(fileName, fontWeight = FontWeight.SemiBold)
                                if (filePath.isNotBlank()) {
                                    TextButton(onClick = { onOpenFolder(filePath) },
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.height(24.dp)) {
                                        Text(s.showInFolder, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    isClipboard -> {
                        val clipText = String(msg.payload)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📋")
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(s.clipboardMessage, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold)
                                Text(clipText.take(200), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onCopyClipboard(clipText) },
                                contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(s.copyToClipboard, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    else -> {
                        Text(
                            renderMarkdown(String(msg.payload)),
                            color = if (isEmergency) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (msg.isEdited) {
                    Text("(edited)", style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Reactions
                if (reactions.isNotEmpty()) {
                    val grouped = reactions.groupBy { it }.mapValues { it.value.size }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        grouped.forEach { (emoji, count) ->
                            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp)) {
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
                        // Delivery status
                        Text(
                            when {
                                msg.isAcknowledged -> " ✓✓"
                                msg.pendingDelivery -> " ⏳"
                                else -> " ✓"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                msg.isAcknowledged -> MaterialTheme.colorScheme.primary
                                msg.pendingDelivery -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        // Read receipts
                        if (readCount > 0) {
                            Text(" 👁 $readCount", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
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
                DropdownMenuItem(text = { Text(s.reply) }, onClick = { onReply(); showMenu = false })
                DropdownMenuItem(text = { Text(s.react) }, onClick = { showEmojiPicker = true; showMenu = false })
                if (isMine && !isFileComplete && !isClipboard)
                    DropdownMenuItem(text = { Text(s.edit) }, onClick = { onEdit(); showMenu = false })
                DropdownMenuItem(text = { Text(if (msg.isPinned) s.unpin else s.pin) },
                    onClick = { onPin(); showMenu = false })
                DropdownMenuItem(text = { Text(if (isStarred) s.unstar else s.star) },
                    onClick = { onStar(); showMenu = false })
                DropdownMenuItem(text = { Text(s.forward) },
                    onClick = { onForward(); showMenu = false })
                if (isMine) DropdownMenuItem(
                    text = { Text(s.delete, color = MaterialTheme.colorScheme.error) },
                    onClick = { onDelete(); showMenu = false })
            }

            if (showEmojiPicker) {
                DropdownMenu(expanded = true, onDismissRequest = { showEmojiPicker = false }) {
                    val emojis = listOf("👍","❤️","😂","😮","😢","😡","🔥","✅","👀","🎉","💯","🙏")
                    Row(modifier = Modifier.padding(8.dp)) {
                        emojis.chunked(6).forEach { row ->
                            Column {
                                row.forEach { emoji ->
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
    }
}

private fun renderMarkdown(text: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        // Tokenize: split by **bold**, _italic_, `code`
        val pattern = Regex("""\*\*(.+?)\*\*|_(.+?)_|`(.+?)`""")
        var last = 0
        for (match in pattern.findAll(text)) {
            append(text.substring(last, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                match.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(match.groupValues[2]) }
                match.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x22000000))) { append(match.groupValues[3]) }
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
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
