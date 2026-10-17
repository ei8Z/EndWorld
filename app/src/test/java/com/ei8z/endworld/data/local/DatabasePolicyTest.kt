package com.ei8z.endworld.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabasePolicyTest {

    @Test
    fun `release is safe and debug is permissive`() {
        assertEquals(10, DatabasePolicy.VERSION)
        assertFalse(DatabasePolicy.destructiveFallbackEnabled(debug = false))
        assertTrue(DatabasePolicy.destructiveFallbackEnabled(debug = true))
    }
}
