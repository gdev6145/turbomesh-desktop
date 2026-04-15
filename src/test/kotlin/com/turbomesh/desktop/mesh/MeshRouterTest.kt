package com.turbomesh.desktop.mesh

import kotlin.test.Test
import kotlin.test.assertEquals

class MeshRouterTest {
    @Test fun `getPath returns empty list for direct route`() {
        val router = MeshRouter()
        router.registerDirectRoute("nodeA")
        assertEquals(emptyList(), router.getPath("nodeA"))
    }

    @Test fun `getPath returns hop list for multi-hop route`() {
        val router = MeshRouter()
        router.registerRoute("nodeC", listOf("nodeB", "nodeC"))
        assertEquals(listOf("nodeB", "nodeC"), router.getPath("nodeC"))
    }

    @Test fun `getPath returns empty list for unknown node`() {
        val router = MeshRouter()
        assertEquals(emptyList(), router.getPath("unknown"))
    }
}
