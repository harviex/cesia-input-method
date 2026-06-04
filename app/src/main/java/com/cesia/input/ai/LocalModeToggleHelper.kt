package com.cesia.input.ai

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.cesia.input.model.ModelManager

/**
 * 本地化切换按钮逻辑
 *
 * 管理键盘工具栏上的「📱/🌐/💰」按钮:
 * - 显示当前模式图标
 * - 点击切换本地↔云端
 * - 缺失模型/API 时弹窗引导
 */
class LocalModeToggleHelper(
    private val context: Context,
    private val localModeManager: LocalModeManager,
    private val modelManager: ModelManager,
    private val onModeChanged: (() -> Unit)? = null
) {
    private var btnLocalMode: TextView? = null

    /** 绑定按钮视图 */
    fun bind(button: TextView?) {
        this.btnLocalMode = button
        updateIcon()
    }

    /** 设置点击监听（在 InputMethod 初始化后调用） */
    fun setupListener() {
        btnLocalMode?.setOnClickListener { onToggleClicked() }
    }

    /** 刷新按钮图标 */
    fun updateIcon() {
        val emoji = when (localModeManager.mode) {
            LocalModeManager.RunMode.LOCAL -> "\uD83D\uDCF1"   // 📱
            LocalModeManager.RunMode.CLOUD_FREE -> "\uD83C\uDF10" // 🌐
            LocalModeManager.RunMode.CLOUD_PAID -> "\uD83D\uDCB0" // 💰
        }
        btnLocalMode?.text = emoji
    }

    // ==================== 点击逻辑 ====================

    private fun onToggleClicked() {
        // 计算切换后的目标模式
        val targetMode = when (localModeManager.mode) {
            LocalModeManager.RunMode.LOCAL -> LocalModeManager.RunMode.CLOUD_FREE
            LocalModeManager.RunMode.CLOUD_FREE -> LocalModeManager.RunMode.LOCAL
            LocalModeManager.RunMode.CLOUD_PAID -> LocalModeManager.RunMode.LOCAL
        }
        // 检查目标模式是否可用
        val available = when (targetMode) {
            LocalModeManager.RunMode.LOCAL -> modelManager.hasVoiceModel()
            LocalModeManager.RunMode.CLOUD_FREE, LocalModeManager.RunMode.CLOUD_PAID -> true
        }
        val reason = when (targetMode) {
            LocalModeManager.RunMode.LOCAL -> "本地语音模型未安装，请前往设置下载"
            else -> null
        }

        if (available) {
            // 当前模式可用，直接切换
            localModeManager.toggle()
            updateIcon()
            // 通知外部（更新 VoiceEngine backend）
            onModeChanged?.invoke()
            // 显示切换提示
            Toast.makeText(context, localModeManager.getModeDisplayName(), Toast.LENGTH_SHORT).show()
        } else {
            // 当前模式不可用，引导用户
            showMissingPrompt(reason ?: "无法使用当前模式")
        }
    }

    /** 缺失模型或 API 时的引导弹窗 */
    private fun showMissingPrompt(reason: String) {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("切换模式")
            .setMessage(reason)
            .setPositiveButton("前往设置") { _, _ ->
                try {
                    val intent = Intent(context, com.cesia.input.SettingsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, "请手动打开 Cesia 设置", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()
        // 输入法中弹 dialog 需要设置窗口类型
        dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        dialog.show()
    }
}
