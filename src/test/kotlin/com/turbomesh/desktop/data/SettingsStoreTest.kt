package com.turbomesh.desktop.data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SettingsStoreTest {
    @Test fun `telemetry flag persists`() {
        val store = SettingsStore()
        val before = store.current()
        // Toggle telemetry to true then false to ensure persistence path is exercised
        store.update(before.copy(telemetryEnabled = true))
        assertTrue(store.current().telemetryEnabled)
        store.update(before.copy(telemetryEnabled = false))
        assertFalse(store.current().telemetryEnabled)
    }
}
