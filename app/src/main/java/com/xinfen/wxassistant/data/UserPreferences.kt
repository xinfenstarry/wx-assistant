package com.xinfen.wxassistant.data

import android.content.Context
import java.util.UUID

class UserPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val secureApiKeyStore = SecureApiKeyStore(context)

    var disclosureAccepted: Boolean
        get() = preferences.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        set(value) = preferences.edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, value).apply()

    var retentionDays: Int
        get() = preferences.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS).coerceIn(1, 90)
        set(value) = preferences.edit().putInt(KEY_RETENTION_DAYS, value.coerceIn(1, 90)).apply()

    var scanRequestedAt: Long
        get() = preferences.getLong(KEY_SCAN_REQUESTED_AT, 0L)
        set(value) = preferences.edit().putLong(KEY_SCAN_REQUESTED_AT, value).apply()

    var lastDeepSeekRunAt: Long
        get() = preferences.getLong(KEY_LAST_DEEPSEEK_RUN_AT, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_DEEPSEEK_RUN_AT, value).apply()

    var deepSeekApiKey: String
        get() = secureApiKeyStore.read()
        set(value) = secureApiKeyStore.write(value)

    fun beginDeepSeekExchange(now: Long = System.currentTimeMillis()): String {
        val token = UUID.randomUUID().toString()
        preferences.edit()
            .putString(KEY_DEEPSEEK_EXCHANGE_TOKEN, token)
            .putLong(KEY_DEEPSEEK_EXCHANGE_CREATED_AT, now)
            .apply()
        return token
    }

    fun isValidDeepSeekExchange(token: String, now: Long = System.currentTimeMillis()): Boolean {
        val pending = preferences.getString(KEY_DEEPSEEK_EXCHANGE_TOKEN, null) ?: return false
        val createdAt = preferences.getLong(KEY_DEEPSEEK_EXCHANGE_CREATED_AT, 0L)
        return token == pending && createdAt > 0L && now - createdAt in 0L..EXCHANGE_TTL_MS
    }

    fun consumeDeepSeekExchange(token: String) {
        if (preferences.getString(KEY_DEEPSEEK_EXCHANGE_TOKEN, null) != token) return
        preferences.edit()
            .remove(KEY_DEEPSEEK_EXCHANGE_TOKEN)
            .remove(KEY_DEEPSEEK_EXCHANGE_CREATED_AT)
            .apply()
    }

    fun isRecentScanRequest(now: Long = System.currentTimeMillis()): Boolean =
        scanRequestedAt > 0 && now - scanRequestedAt <= SCAN_REQUEST_TTL_MS

    fun consumeScanRequest() {
        scanRequestedAt = 0L
    }

    companion object {
        private const val FILE_NAME = "wx_assistant_preferences"
        private const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_SCAN_REQUESTED_AT = "scan_requested_at"
        private const val KEY_LAST_DEEPSEEK_RUN_AT = "last_deepseek_run_at"
        private const val KEY_DEEPSEEK_EXCHANGE_TOKEN = "deepseek_exchange_token"
        private const val KEY_DEEPSEEK_EXCHANGE_CREATED_AT = "deepseek_exchange_created_at"
        private const val DEFAULT_RETENTION_DAYS = 30
        private const val SCAN_REQUEST_TTL_MS = 2 * 60 * 1000L
        private const val EXCHANGE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
