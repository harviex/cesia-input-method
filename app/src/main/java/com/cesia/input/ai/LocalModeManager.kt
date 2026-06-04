package com.cesia.input.ai

import android.content.Context
import android.content.SharedPreferences
import com.cesia.input.model.ModelManager

/**
 * 本地/云端模式管理器
 *
 * 管理运行模式切换，协调 VoiceEngine 和 AIEngine
 */
class LocalModeManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "cesia_local_mode"
        private const val KEY_MODE = "run_mode"
    }

    enum class RunMode {
        LOCAL,      // 本地模式：Whisper + Llama.cpp
        CLOUD_FREE, // 云端免费：Groq + OpenRouter 免费模型
        CLOUD_PAID  // 云端付费：Groq + OpenRouter 付费模型
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val modelManager = ModelManager(context)

    /** 当前运行模式 */
    var mode: RunMode
        get() {
            val name = prefs.getString(KEY_MODE, RunMode.LOCAL.name) ?: RunMode.LOCAL.name
            return try { RunMode.valueOf(name) } catch (_: Exception) { RunMode.LOCAL }
        }
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** 切换到下一个模式 */
    fun toggle(): RunMode {
        mode = when (mode) {
            RunMode.LOCAL -> RunMode.CLOUD_FREE
            RunMode.CLOUD_FREE -> RunMode.LOCAL
            RunMode.CLOUD_PAID -> RunMode.LOCAL
        }
        return mode
    }

    /** 本地模式是否就绪（模型已安装） */
    fun isLocalReady(): Boolean = modelManager.isLocalFullySetup()

    /** 云端模式是否有 API Key */
    private fun hasCloudKeys(): Boolean {
        val settings = context.getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
        val openRouterKey = settings.getString("open_router_api_key", "")
        val groqKey = settings.getString("groq_api_key", "")
        return !openRouterKey.isNullOrBlank() || !groqKey.isNullOrBlank()
    }

    /**
     * 当前模式是否可用
     * 本地模式：至少安装了语音模型（Whisper）即可（润色可回退到云端）
     * 云端模式：有 API Key 或没有本地模型时可用
     */
    fun checkAvailability(): Pair<Boolean, String?> {
        return when (mode) {
            RunMode.LOCAL -> {
                // 切换到本地模式：至少需要语音模型
                if (modelManager.hasVoiceModel()) {
                    true to null
                } else {
                    false to "本地语音模型未安装，请前往设置下载"
                }
            }
            RunMode.CLOUD_FREE, RunMode.CLOUD_PAID -> {
                // 切换到云端模式：总是可以（有 key 用 key，没 key 也能用 Google）
                true to null
            }
        }
    }

    /** 获取当前模式显示名 */
    fun getModeDisplayName(): String = when (mode) {
        RunMode.LOCAL -> "本地模式"
        RunMode.CLOUD_FREE -> "云端免费"
        RunMode.CLOUD_PAID -> "云端付费"
    }

    /** 获取当前模式简短显示名（用于按钮） */
    fun getModeShortName(): String = when (mode) {
        RunMode.LOCAL -> "📱"
        RunMode.CLOUD_FREE -> "🌐"
        RunMode.CLOUD_PAID -> "💰"
    }
}
