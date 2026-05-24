package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log

/**
 * Rime JNI 桥接层
 * 所有 native 调用都通过此单例路由
 */
object RimeJni {

    private const val TAG = "RimeJni"
    private const val NATIVE_LIBRARY_NAME = "cesia-rime-engine"

    private val nativeLibraryLock = Any()
    @Volatile
    private var nativeLibraryLoaded = false
    @Volatile
    private var nativeLibraryErrorMessage: String? = null
    @Volatile
    private var initialized = false

    /**
     * 检查 native 库是否可用
     */
    fun isAvailable(): Boolean = ensureNativeLibraryLoaded()

    /**
     * 获取不可用原因
     */
    fun unavailableMessage(): String? {
        ensureNativeLibraryLoaded()
        return nativeLibraryErrorMessage
    }

    /**
     * 初始化 Rime 引擎
     * @param context Android 上下文
     * @return true 表示初始化成功
     */
    fun initialize(context: Context): Boolean {
        if (!ensureNativeLibraryLoaded()) return false
        if (initialized) return true

        // 从 assets 复制 rime 数据文件到 filesDir
        copyRimeAssets(context)

        val dataDir = context.filesDir.resolve("rime").absolutePath
        val sharedDir = dataDir // 简化：使用同一目录

        return try {
            nativeInitialize(dataDir, sharedDir)
            initialized = true
            Log.i(TAG, "Rime 引擎初始化成功: dataDir=$dataDir")
            true
        } catch (error: UnsatisfiedLinkError) {
            nativeLibraryErrorMessage = error.message ?: "无法初始化 Rime 引擎"
            Log.e(TAG, "Rime 引擎初始化失败", error)
            false
        } catch (error: Exception) {
            nativeLibraryErrorMessage = error.message ?: "Rime 引擎初始化异常"
            Log.e(TAG, "Rime 引擎初始化异常", error)
            false
        }
    }

    /**
     * 关闭 Rime 引擎
     */
    fun shutdown() {
        if (!nativeLibraryLoaded) return
        if (!initialized) return
        try {
            nativeShutdown()
        } catch (_: Exception) {}
        initialized = false
    }

    /**
     * 创建新的输入会话
     */
    fun createSession(): RimeSession {
        val sessionId = nativeCreateSession()
        return RimeSession(sessionId)
    }

    /**
     * 销毁输入会话
     */
    fun destroySession(session: RimeSession) {
        nativeDestroySession(session.id)
    }

    // --- 代理方法 ---

    fun processKey(sessionId: Long, key: String): Boolean {
        return nativeProcessKey(sessionId, key)
    }

    fun getComposingText(sessionId: Long): String {
        return nativeGetComposingText(sessionId)
    }

    fun getCandidates(sessionId: Long): List<String> {
        return nativeGetCandidates(sessionId).toList()
    }

    fun commitComposition(sessionId: Long): String {
        return nativeCommitComposition(sessionId)
    }

    fun selectCandidate(sessionId: Long, index: Int): String {
        return nativeSelectCandidate(sessionId, index)
    }

    fun clearComposition(sessionId: Long) {
        nativeClearComposition(sessionId)
    }

    fun changePage(sessionId: Long, backward: Boolean): Boolean {
        return nativeChangePage(sessionId, backward)
    }

    fun getPageCount(sessionId: Long): Int {
        return nativeGetPageCount(sessionId)
    }

    fun getCurrentPage(sessionId: Long): Int {
        return nativeGetCurrentPage(sessionId)
    }

    // --- 内部方法 ---

    private fun copyRimeAssets(context: Context) {
        val rimeDir = context.filesDir.resolve("rime")
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        try {
            val assetManager = context.assets
            val files = assetManager.list("rime") ?: return
            for (fileName in files) {
                val destFile = rimeDir.resolve(fileName)
                if (destFile.exists()) continue // 已存在则跳过
                try {
                    assetManager.open("rime/$fileName").use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "复制 rime 资源失败: $fileName", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "复制 rime 资源失败", e)
        }
    }

    private fun ensureNativeLibraryLoaded(): Boolean {
        if (nativeLibraryLoaded) return true
        if (nativeLibraryErrorMessage != null) return false

        synchronized(nativeLibraryLock) {
            if (nativeLibraryLoaded) return true
            if (nativeLibraryErrorMessage != null) return false

            return try {
                System.loadLibrary(NATIVE_LIBRARY_NAME)
                nativeLibraryLoaded = true
                Log.i(TAG, "Native 库加载成功: $NATIVE_LIBRARY_NAME")
                true
            } catch (error: UnsatisfiedLinkError) {
                nativeLibraryErrorMessage = error.message ?: "无法加载 $NATIVE_LIBRARY_NAME"
                Log.e(TAG, "Native 库加载失败: $NATIVE_LIBRARY_NAME", error)
                false
            }
        }
    }

    // --- Native 方法声明 ---

    private external fun nativeInitialize(dataDir: String, sharedDir: String)
    private external fun nativeShutdown()
    private external fun nativeCreateSession(): Long
    private external fun nativeDestroySession(sessionId: Long)
    private external fun nativeProcessKey(sessionId: Long, key: String): Boolean
    private external fun nativeGetComposingText(sessionId: Long): String
    private external fun nativeGetCandidates(sessionId: Long): Array<String>
    private external fun nativeCommitComposition(sessionId: Long): String
    private external fun nativeSelectCandidate(sessionId: Long, index: Int): String
    private external fun nativeClearComposition(sessionId: Long)
    private external fun nativeChangePage(sessionId: Long, backward: Boolean): Boolean
    private external fun nativeGetPageCount(sessionId: Long): Int
    private external fun nativeGetCurrentPage(sessionId: Long): Int
}
