package com.example.tscprint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSharePolicyTest {

    private val send = "android.intent.action.SEND"
    private val view = "android.intent.action.VIEW"

    @Test
    fun disabledQuickShareAlwaysOpensPreview() {
        assertFalse(QuickSharePolicy.shouldPrintSilently(false, view, null, emptySet()))
        assertFalse(QuickSharePolicy.shouldPrintSilently(false, send, "com.example.sender", setOf("com.example.sender")))
    }

    @Test
    fun knownSourceMustBeSelected() {
        assertTrue(QuickSharePolicy.shouldPrintSilently(true, send, "com.example.allowed", setOf("com.example.allowed")))
        assertFalse(QuickSharePolicy.shouldPrintSilently(true, send, "com.example.blocked", setOf("com.example.allowed")))
    }

    @Test
    fun unknownShareSourceOpensPreview() {
        assertFalse(QuickSharePolicy.shouldPrintSilently(true, send, null, emptySet()))
    }

    @Test
    fun explicitOpenWithQuickPrintPrintsSilently() {
        assertTrue(QuickSharePolicy.shouldPrintSilently(true, view, null, emptySet()))
    }
}
