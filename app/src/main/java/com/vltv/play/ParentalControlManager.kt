package com.vltv.play

import android.content.Context

object ParentalControlManager {

    private const val PREFS = "vltv_prefs"
    private const val KEY_PIN = "parental_pin"
    private const val KEY_ENABLED = "parental_enabled"
    private const val KEY_BLOCKED_CATEGORIES = "blocked_categories"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getPin(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // PIN padrão 0000
        return prefs.getString(KEY_PIN, "0000") ?: "0000"
    }

    fun setPin(context: Context, pin: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    fun getBlockedCategories(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_BLOCKED_CATEGORIES, emptySet()) ?: emptySet()
    }

    fun toggleCategoryBlocked(context: Context, categoryId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_BLOCKED_CATEGORIES, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        if (current.contains(categoryId)) current.remove(categoryId) else current.add(categoryId)
        prefs.edit().putStringSet(KEY_BLOCKED_CATEGORIES, current).apply()
    }

    fun isCategoryBlocked(context: Context, categoryId: String): Boolean {
        return getBlockedCategories(context).contains(categoryId)
    }
}
