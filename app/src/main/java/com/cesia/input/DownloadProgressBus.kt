package com.cesia.input

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 跨组件下载进度总线。
 * 设置页(SettingsActivity)里跑的模型/词库/语音下载，进度通过这里同时广播给：
 *  - 设置页自身的进度条(pbVersionDownload)
 *  - 输入法键盘状态栏(CesiaInputMethod.updateStatus)
 * 二者订阅同一进度，实现“键盘状态栏与设置页进度同步显示”。
 */
object DownloadProgressBus {

    /** 当前正在下载的任务标签，空串表示无下载 */
    var currentTask: String = ""
        private set

    data class Progress(
        val task: String,      // 任务名（如 "手机AI模型" / "语音文字套件" / "雾凇词库"）
        val percent: Double,   // 0.0 ~ 100.0
        val done: Boolean,     // 是否完成（percent=100 且结束）
        val failed: Boolean    // 是否失败
    )

    private val listeners = CopyOnWriteArrayList<(Progress) -> Unit>()

    fun subscribe(listener: (Progress) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun emit(task: String, percent: Double, done: Boolean = false, failed: Boolean = false) {
        currentTask = if (done || failed) "" else task
        val p = Progress(task, percent.coerceIn(0.0, 100.0), done, failed)
        for (l in listeners) l(p)
    }

    fun clear() {
        currentTask = ""
    }
}
