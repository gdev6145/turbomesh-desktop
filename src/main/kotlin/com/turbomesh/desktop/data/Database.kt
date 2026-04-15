package com.turbomesh.desktop.data

import com.turbomesh.desktop.mesh.MeshMessage
import com.turbomesh.desktop.mesh.MeshMessageType
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Paths

object MessagesTable : Table("messages") {
    val id = varchar("id", 64)
    val sourceNodeId = varchar("source_node_id", 64)
    val destinationNodeId = varchar("destination_node_id", 64)
    val type = varchar("type", 32)
    val payload = binary("payload", 8192)
    val timestamp = long("timestamp")
    val hopCount = integer("hop_count")
    val ttl = integer("ttl")
    val isAcknowledged = bool("is_acknowledged").default(false)
    val readAtMs = long("read_at_ms").nullable()
    val pendingDelivery = bool("pending_delivery").default(false)
    val replyToMsgId = varchar("reply_to_msg_id", 64).nullable()
    val isEdited = bool("is_edited").default(false)
    val editedAtMs = long("edited_at_ms").nullable()
    val deletedAtMs = long("deleted_at_ms").nullable()
    val isPinned = bool("is_pinned").default(false)
    val scheduledAtMs = long("scheduled_at_ms").nullable()
    val expiresAtMs = long("expires_at_ms").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RssiLogTable : LongIdTable("rssi_log") {
    val nodeId = varchar("node_id", 64)
    val rssi = integer("rssi")
    val timestampMs = long("timestamp_ms")
}

object AppDatabase {
    private val dbPath = Paths.get(
        System.getProperty("user.home"), ".turbomesh", "turbomesh.db"
    ).also { it.parent.toFile().mkdirs() }.toString()

    fun connect() {
        Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(MessagesTable, RssiLogTable)
        }
    }

    fun insertMessage(msg: MeshMessage) = transaction {
        MessagesTable.replace {
            it[id] = msg.id; it[sourceNodeId] = msg.sourceNodeId
            it[destinationNodeId] = msg.destinationNodeId; it[type] = msg.type.name
            it[payload] = msg.payload; it[timestamp] = msg.timestamp
            it[hopCount] = msg.hopCount; it[ttl] = msg.ttl
            it[isAcknowledged] = msg.isAcknowledged; it[readAtMs] = msg.readAtMs
            it[pendingDelivery] = msg.pendingDelivery; it[replyToMsgId] = msg.replyToMsgId
            it[isEdited] = msg.isEdited; it[editedAtMs] = msg.editedAtMs
            it[deletedAtMs] = msg.deletedAtMs; it[isPinned] = msg.isPinned
            it[scheduledAtMs] = msg.scheduledAtMs; it[expiresAtMs] = msg.expiresAtMs
        }
    }

    fun getAllMessages(): List<MeshMessage> = transaction {
        MessagesTable.selectAll().orderBy(MessagesTable.timestamp).map { rowToMessage(it) }
    }

    fun deleteMessage(msgId: String) = transaction {
        MessagesTable.deleteWhere { MessagesTable.id eq msgId }
    }

    fun clearMessages() = transaction { MessagesTable.deleteAll() }

    fun markAcknowledged(msgId: String) = transaction {
        MessagesTable.update({ MessagesTable.id eq msgId }) { 
            it[isAcknowledged] = true 
            it[pendingDelivery] = false
        }
    }

    fun markRead(msgId: String, atMs: Long) = transaction {
        MessagesTable.update({ MessagesTable.id eq msgId }) { it[readAtMs] = atMs }
    }

    fun setPinned(msgId: String, pinned: Boolean) = transaction {
        MessagesTable.update({ MessagesTable.id eq msgId }) { it[isPinned] = pinned }
    }

    fun markEdited(msgId: String, newPayload: ByteArray, atMs: Long) = transaction {
        MessagesTable.update({ MessagesTable.id eq msgId }) {
            it[payload] = newPayload; it[isEdited] = true; it[editedAtMs] = atMs
        }
    }

    fun markDeleted(msgId: String, atMs: Long) = transaction {
        MessagesTable.update({ MessagesTable.id eq msgId }) { it[deletedAtMs] = atMs }
    }

    fun getPinnedMessages(): List<MeshMessage> = transaction {
        MessagesTable.selectAll().where { MessagesTable.isPinned eq true }
            .orderBy(MessagesTable.timestamp).map { rowToMessage(it) }
    }

    fun getPendingMessages(nodeId: String): List<MeshMessage> = transaction {
        MessagesTable.selectAll().where { (MessagesTable.destinationNodeId eq nodeId) and (MessagesTable.pendingDelivery eq true) }
            .orderBy(MessagesTable.timestamp).map { rowToMessage(it) }
    }

    fun logRssi(nodeId: String, rssi: Int) = transaction {
        RssiLogTable.insert { it[RssiLogTable.nodeId] = nodeId; it[RssiLogTable.rssi] = rssi; it[timestampMs] = System.currentTimeMillis() }
    }

    private fun rowToMessage(row: ResultRow) = MeshMessage(
        id = row[MessagesTable.id],
        sourceNodeId = row[MessagesTable.sourceNodeId],
        destinationNodeId = row[MessagesTable.destinationNodeId],
        type = runCatching { MeshMessageType.valueOf(row[MessagesTable.type]) }.getOrDefault(MeshMessageType.DATA),
        payload = row[MessagesTable.payload],
        timestamp = row[MessagesTable.timestamp],
        hopCount = row[MessagesTable.hopCount],
        ttl = row[MessagesTable.ttl],
        isAcknowledged = row[MessagesTable.isAcknowledged],
        readAtMs = row[MessagesTable.readAtMs],
        pendingDelivery = row[MessagesTable.pendingDelivery],
        replyToMsgId = row[MessagesTable.replyToMsgId],
        isEdited = row[MessagesTable.isEdited],
        editedAtMs = row[MessagesTable.editedAtMs],
        deletedAtMs = row[MessagesTable.deletedAtMs],
        isPinned = row[MessagesTable.isPinned],
        scheduledAtMs = row[MessagesTable.scheduledAtMs],
        expiresAtMs = row[MessagesTable.expiresAtMs],
    )
}
