package com.vltv.play.data

import android.content.Context

object AuthManager {
    /**
     * Retorna (dns, user, pass) atuais do SharedPreferences
     */
    fun getCurrentCredentials(context: Context): Triple<String, String, String>? {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns = prefs.getString("dns", null) ?: return null
        val user = prefs.getString("username", null) ?: return null
        val pass = prefs.getString("password", null) ?: return null
        return Triple(dns, user, pass)
    }
    
    /**
     * Retorna (serverUrl, username) para filtrar banco
     */
    fun getCurrentServerUser(context: Context): Pair<String, String>? {
        val creds = getCurrentCredentials(context) ?: return null
        return Pair(creds.first, creds.second)
    }
}
