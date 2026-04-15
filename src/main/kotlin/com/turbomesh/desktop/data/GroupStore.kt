package com.turbomesh.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.prefs.Preferences

data class MeshGroup(
    val id: String,
    val name: String,
    val members: Set<String>,
    val createdByMe: Boolean = true,
)

class GroupStore {
    private val prefs = Preferences.userRoot().node("turbomesh/groups")
    private val _groups = MutableStateFlow(loadAll())
    val groups: StateFlow<List<MeshGroup>> = _groups.asStateFlow()

    fun createGroup(name: String, members: Set<String>): MeshGroup {
        val id = "GRP_${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
        val group = MeshGroup(id, name, members, createdByMe = true)
        save(group)
        _groups.value = loadAll()
        return group
    }

    /** Called when we receive a GROUP_INVITE from another node. */
    fun addFromInvite(groupId: String, name: String, members: Set<String>) {
        if (_groups.value.any { it.id == groupId }) return
        val group = MeshGroup(groupId, name, members, createdByMe = false)
        save(group)
        _groups.value = loadAll()
    }

    fun addMember(groupId: String, nodeId: String) {
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return
        save(group.copy(members = group.members + nodeId))
        _groups.value = loadAll()
    }

    fun deleteGroup(groupId: String) {
        prefs.remove("${groupId}_name")
        prefs.remove("${groupId}_members")
        prefs.remove("${groupId}_mine")
        _groups.value = loadAll()
    }

    fun getGroup(groupId: String): MeshGroup? = _groups.value.firstOrNull { it.id == groupId }

    fun isGroupId(id: String) = id.startsWith("GRP_")

    private fun save(group: MeshGroup) {
        prefs.put("${group.id}_name", group.name)
        prefs.put("${group.id}_members", group.members.joinToString(","))
        prefs.putBoolean("${group.id}_mine", group.createdByMe)
    }

    private fun loadAll(): List<MeshGroup> {
        val keys = runCatching { prefs.keys() }.getOrElse { emptyArray() }
        val ids = keys.filter { it.endsWith("_name") }.map { it.removeSuffix("_name") }
        return ids.mapNotNull { id ->
            val name = prefs.get("${id}_name", null) ?: return@mapNotNull null
            val members = prefs.get("${id}_members", "")
                .split(",").filter { it.isNotBlank() }.toSet()
            val mine = prefs.getBoolean("${id}_mine", true)
            MeshGroup(id, name, members, createdByMe = mine)
        }.sortedBy { it.name }
    }
}
