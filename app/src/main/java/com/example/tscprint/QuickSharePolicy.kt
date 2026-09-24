package com.example.tscprint

object QuickSharePolicy {
    fun shouldPrintSilently(
        enabled: Boolean,
        action: String?,
        sourcePackage: String?,
        allowedPackages: Set<String>
    ): Boolean = enabled && when (action) {
        "android.intent.action.VIEW" -> true
        "android.intent.action.SEND" -> sourcePackage != null && sourcePackage in allowedPackages
        else -> false
    }
}
