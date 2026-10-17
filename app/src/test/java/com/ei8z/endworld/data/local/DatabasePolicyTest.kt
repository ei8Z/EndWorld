package com.ei8z.endworld.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabasePolicyTest {

    @Test
    fun `release build never enables destructive fallback`() {
        assertFalse(DatabasePolicy.destructiveFallbackEnabled(debug = false))
    }

    @Test
    fun `debug build keeps destructive fallback for fast iteration`() {
        assertTrue(DatabasePolicy.destructiveFallbackEnabled(debug = true))
    }

    @Test
    fun `schema version is pinned`() {
        assertEquals(10, DatabasePolicy.VERSION)
    }
}
