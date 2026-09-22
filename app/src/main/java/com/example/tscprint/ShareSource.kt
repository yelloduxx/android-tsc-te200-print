package com.example.tscprint

import android.app.Activity
import android.content.Intent
import android.net.Uri

object ShareSource {
    const val EXTRA_SOURCE_PACKAGE = "com.example.tscprint.EXTRA_SOURCE_PACKAGE"

    fun packageName(activity: Activity, intent: Intent): String? {
        val referrerPackage = activity.referrer?.host
            ?.takeIf { activity.referrer?.scheme == "android-app" }
        return activity.callingPackage
            ?: referrerPackage
            ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)
                ?.takeIf { it.scheme == "android-app" }
                ?.host
            ?: intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)
                ?.removePrefix("android-app://")
                ?.substringBefore('/')
    }
}
