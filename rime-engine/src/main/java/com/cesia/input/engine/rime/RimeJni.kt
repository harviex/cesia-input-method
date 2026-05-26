package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log
import com.osfans.trime.core.Rime as TrimeRime
import java.io.File

/**
 * Rime JNI 桥接层（Cesia 封装）
 * 通过 com.osfans.trime.core.Rime 包下的 native 方法调用 librime_jni.so
 *
 * 设计参考 Trime 的 RimeApi：
 * - processKey 不关心返回值，按键后通过 getRimeContext/getRimeStatus 轮询状态
 * - selectCandidate 选中后自动提交
 * - commitComposition 上屏当前组合
 */
object RimeJni {

    private const val TAG = "RimeJni"

    @Volatile
    private var initialized = false

    fun isAvailable(): Boolean = initialized

    @Volatile
    private var errorMessage: String? = null

    fun unavailableMessage(): String? = errorMessage

    // ======================== 生命周期 ========================

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        errorMessage = null
        try {
            System.loadLibrary("rime_jni")
            Log.i(TAG, "STEP1: librime_jni.so 加载成功")

            // 使用外部存储目录，避免卸载时丢失词库
            val rimeDir = File(context.getExternalFilesDir(null), "rime")
            if (!rimeDir.exists()) rimeDir.mkdirs()
            val sharedDir = rimeDir.absolutePath
            val userDir = rimeDir.absolutePath

            // 列出 rime 目录下文件，用于诊断
            val rimeDirFiles = rimeDir.listFiles()?.map { "${it.name}(${it.length()})" }?.joinToString(", ") ?: "(空)"
            Log.i(TAG, "STEP2: rime 目录内容: $rimeDirFiles")

            // 清除旧 build 目录，强制重新编译
            val buildDir = File(rimeDir, "build")
            if (buildDir.exists()) {
                buildDir.deleteRecursively()
                Log.i(TAG, "STEP3: 清除旧 build 目录")
            }

            TrimeRime.startupRime(sharedDir, userDir, "1.0.0", true)
            Log.i(TAG, "STEP4: startupRime 完成")

            // 确保选中 pinyin schema
            val currentSchema = TrimeRime.getCurrentRimeSchema()
            Log.i(TAG, "STEP4b: currentSchema=$currentSchema")
            if (currentSchema != "pinyin") {
                val selectResult = TrimeRime.selectRimeSchema("pinyin")
                Log.i(TAG, "STEP4c: selectRimeSchema(pinyin)=$selectResult, after=${TrimeRime.getCurrentRimeSchema()}")
            }

            val started = isRimeStarted()
            if (!started) {
                errorMessage = "startupRime 后 isRimeStarted=false (共享目录: $sharedDir)"
                Log.e(TAG, errorMessage!!)
            } else {
                try {
                    val status = TrimeRime.getRimeStatus()
                    Log.i(TAG, "STEP5: RimeStatus schema=${status.schemaId} disabled=${status.isDisabled} composing=${status.isComposing}")
                } catch (e: Throwable) {
                    Log.w(TAG, "STEP5: getRimeStatus 失败: ${e.message}")
                }
            }
            initialized = started
            return started
        } catch (e: Throwable) {
            errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Rime native 引擎初始化失败", e)
            initialized = false
            return false
        }
    }

    fun shutdown() {
        if (initialized) {
            try { TrimeRime.exitRime() } catch (_: Exception) {}
            initialized = false
        }
    }

    // ======================== 状态查询 ========================

    fun isComposing(): Boolean {
        return try {
            TrimeRime.getRimeStatus().isComposing
        } catch (_: Throwable) { false }
    }

    fun getComposingText(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            val ctx = TrimeRime.getRimeContext()
            ctx.composition.preedit ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "getComposingText failed", e)
            ""
        }
    }

    /**
     * 获取当前页的候选词列表
     * Rime 的 getRimeContext().menu.candidates 返回的是当前页的候选词
     */
    fun getCandidates(sessionId: Long): List<String> {
        if (!initialized) return emptyList()
        return try {
            val ctx = TrimeRime.getRimeContext()
            ctx.menu.candidates.map { it.text }
        } catch (e: Throwable) {
            Log.e(TAG, "getCandidates failed", e)
            emptyList()
        }
    }

    /**
     * 获取当前菜单的分页信息
     */
    fun getPageInfo(sessionId: Long): PageInfo {
        if (!initialized) return PageInfo(0, 0, false, 0)
        return try {
            val menu = TrimeRime.getRimeContext().menu
            val totalCandidates = menu.candidates.size
            val pageSize = if (menu.pageSize > 0) menu.pageSize else 9
            val currentPage = menu.pageNumber
            val isLastPage = menu.isLastPage
            val totalPages = if (pageSize > 0) (totalCandidates + pageSize - 1) / pageSize else 0
            PageInfo(pageSize, currentPage, isLastPage, totalPages)
        } catch (e: Throwable) {
            Log.e(TAG, "getPageInfo failed", e)
            PageInfo(0, 0, false, 0)
        }
    }

    // ======================== 按键处理 ========================

    /**
     * 处理按键 —— 参考 Trime 的 processKey 逻辑
     * Trime 不关心 processKey 的返回值，按键后通过 messageFlow 回调更新 UI
     * 我们这里也不检查返回值，由调用方在按键后轮询 Rime 状态
     */
    fun processKey(sessionId: Long, key: String): Boolean {
        if (!initialized) return false
        return try {
            val keycode = keyToRimeKeyCode(key)
            TrimeRime.processRimeKey(keycode, 0).also { result ->
                Log.d(TAG, "processKey key=$key keycode=$keycode result=$result composing=${isComposing()}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "processKey failed: $key", e)
            false
        }
    }

    // ======================== 提交/选择 ========================

    fun createSession(): RimeSession = RimeSession(1L)
    fun destroySession(session: RimeSession) {}

    /**
     * 选择候选词并上屏
     * 参考 Trime：selectCandidate(idx) → commitComposition()
     */
    fun selectCandidate(sessionId: Long, index: Int): String {
        if (!initialized) return ""
        return try {
            TrimeRime.selectRimeCandidate(index, false)
            val text = commitComposition(sessionId)
            Log.d(TAG, "selectCandidate index=$index text='$text'")
            text
        } catch (e: Throwable) {
            Log.e(TAG, "selectCandidate failed: index=$index", e)
            ""
        }
    }

    /**
     * 提交当前组合文本（上屏）
     * 参考 Trime 的 commitComposition → getRimeCommit
     */
    fun commitComposition(sessionId: Long): String {
        if (!initialized) return ""
        return try {
            TrimeRime.commitRimeComposition()
            val text = TrimeRime.getRimeCommit().text ?: ""
            Log.d(TAG, "commitComposition text='$text'")
            text
        } catch (e: Throwable) {
            Log.e(TAG, "commitComposition failed", e)
            ""
        }
    }

    fun clearComposition(sessionId: Long) {
        if (!initialized) return
        try { TrimeRime.clearRimeComposition() } catch (_: Throwable) {}
    }

    // ======================== 模式切换 ========================

    fun setAsciiMode(ascii: Boolean) {
        if (!initialized) return
        try {
            TrimeRime.setRimeOption("ascii_mode", ascii)
            Log.d(TAG, "setAsciiMode: $ascii")
        } catch (e: Throwable) {
            Log.e(TAG, "setAsciiMode failed", e)
        }
    }

    // ======================== 翻页 ========================

    fun changePage(sessionId: Long, backward: Boolean): Boolean {
        if (!initialized) return false
        return try {
            TrimeRime.changeRimeCandidatePage(backward)
        } catch (e: Throwable) {
            Log.e(TAG, "changePage failed: backward=$backward", e)
            false
        }
    }

    fun getPageCount(sessionId: Long): Int {
        if (!initialized) return 0
        return try {
            val menu = TrimeRime.getRimeContext().menu
            if (menu.pageSize <= 0) 0
            else {
                // 计算总页数：用候选词总数 / 每页大小
                // menu.candidates 是当前页的候选词，不能用来计算总页数
                // 用 isLastPage 来判断是否还有更多页
                val currentPage = menu.pageNumber
                if (menu.isLastPage) currentPage + 1
                else currentPage + 2 // 至少还有一页
            }
        } catch (e: Throwable) { 0 }
    }

    fun getCurrentPage(sessionId: Long): Int {
        if (!initialized) return 0
        return try {
            TrimeRime.getRimeContext().menu.pageNumber
        } catch (e: Throwable) { 0 }
    }

    // ======================== 内部方法 ========================

    private fun keyToRimeKeyCode(key: String): Int {
        if (key.length == 1) {
            // 字母返回 Unicode 码点（与 Trime 的 KeyValue.fromKeyEvent 一致）
            return key[0].code
        }
        return when (key) {
            "BackSpace", "Back" -> 8
            "Enter", "Return" -> 10
            "Tab" -> 9
            "Escape" -> 27
            "Space" -> 32
            "Delete", "Del" -> 127
            "Up" -> 0xFF52        // XK_Up
            "Down" -> 0xFF54      // XK_Down
            "Left" -> 0xFF51      // XK_Left
            "Right" -> 0xFF53     // XK_Right
            "Home" -> 0xFF50      // XK_Home
            "End" -> 0xFF57       // XK_End
            "PageUp" -> 0xFF55    // XK_Page_Up
            "PageDown" -> 0xFF56  // XK_Page_Down
            else -> {
                Log.w(TAG, "未知按键: $key, 使用 hashCode")
                key.hashCode()
            }
        }
    }

    private fun isRimeStarted(): Boolean {
        return try {
            for (i in 0 until 600) {
                try {
                    val schemas = TrimeRime.getRimeSchemaList()
                    if (schemas.isNotEmpty()) {
                        Log.i(TAG, "isRimeStarted: schemaList=${schemas.size} after ${i * 100}ms")
                        return true
                    }
                } catch (_: Throwable) {}
                Thread.sleep(100)
            }
            Log.e(TAG, "isRimeStarted: timeout after 60s")
            false
        } catch (e: Throwable) {
            Log.e(TAG, "isRimeStarted check failed", e)
            false
        }
    }

    // ======================== 数据类 ========================

    data class PageInfo(
        val pageSize: Int,
        val currentPage: Int,
        val isLastPage: Boolean,
        val totalPages: Int,
    )
}

