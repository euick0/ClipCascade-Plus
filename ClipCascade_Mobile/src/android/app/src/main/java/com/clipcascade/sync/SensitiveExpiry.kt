package com.clipcascade.sync

import android.content.Context

/**
 * A pending 5-minute expiry. Only a hash and a deadline are stored, never the clip, and they
 * survive process death so a restart doesn't leave the clip on other devices forever.
 */
class SensitiveExpiry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sensitive_expiry", Context.MODE_PRIVATE)

    fun arm(clipHash: String, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putString(KEY_HASH, clipHash).putLong(KEY_DEADLINE, nowMillis + LIFETIME_MILLIS).apply()
    }

    fun pending(): Pair<String, Long>? {
        val hash = prefs.getString(KEY_HASH, null) ?: return null
        return hash to prefs.getLong(KEY_DEADLINE, 0)
    }

    fun disarm() = prefs.edit().clear().apply()

    companion object {
        const val LIFETIME_MILLIS = 5 * 60_000L
        private const val KEY_HASH = "hash"
        private const val KEY_DEADLINE = "deadline"
    }
}
