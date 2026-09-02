package com.xinfen.wxassistant.integration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Hands a reviewed plain-text prompt to the installed ChatGPT Android app.
 *
 * This intentionally uses Android's public ACTION_SEND contract. It does not inspect or automate
 * the ChatGPT UI, and it has no callback for reading the answer. The generated summary/table stays
 * in the ChatGPT conversation, which is the explicit product boundary shown to the user.
 */
object ChatGptHandoff {
    const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    fun isChatGptInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    CHATGPT_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(CHATGPT_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun createShareIntent(context: Context, prompt: String): Intent {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        val genericIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "微信群聊摘要与任务表")
            putExtra(Intent.EXTRA_TEXT, prompt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        val directIntent = Intent(genericIntent).setPackage(CHATGPT_PACKAGE)
        return if (
            isChatGptInstalled(context) &&
            directIntent.resolveActivity(context.packageManager) != null
        ) {
            directIntent
        } else {
            Intent.createChooser(genericIntent, "选择 ChatGPT")
        }
    }
}
