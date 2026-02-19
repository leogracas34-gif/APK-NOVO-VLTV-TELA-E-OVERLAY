package com.vltv.play

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

fun Context.isTelevisionDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    val isTvUiMode =
        uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    val pm = packageManager
    val hasTvFeature =
        pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)

    return isTvUiMode || hasTvFeature
}
