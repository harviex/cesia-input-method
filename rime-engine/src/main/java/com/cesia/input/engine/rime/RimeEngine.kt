package com.cesia.input.engine.rime

import android.content.Context
import android.util.Log

/**
 * Rime 输入引擎实现
 * 基于 librime 的完整拼音/五笔/形码输入引擎
 *
 * 当前阶段使用 stub 实现（无 librime native 库），
 * 后续替换为真实 librime 调用
 */
class RimeEngine(private val context: Context) : InputEngine {

    companion object {
        private const val TAG = "RimeEngine"
    }

    private var session: RimeSession? = null

    override val name: String = "Rime"

    override var isInitialized: Boolean = false
        private set

    override val isAvailable: Boolean
        get() = isInitialized && RimeJni.isAvailable()

    // --- 当前会话代理 ---

    override val isComposing: Boolean
        get() = session?.hasComposing() ?: false

    override val composingText: String
        get() = session?.composingText ?: ""

    override val candidates: List<String>
        get() = session?.candidates ?: emptyList()

    override val hasCandidates: Boolean
        get() = session?.hasCandidates() ?: false

    override val pageCount: Int
        get() = session?.pageCount ?: 0

    override val currentPage: Int
        get() = session?.currentPage ?: 0

    // --- 生命周期 ---

    override fun initialize(): Boolean {
        if (isInitialized) return true
        val success = RimeJni.initialize(context)
        isInitialized = success
        if (success) {
            Log.i(TAG, "Rime 引擎初始化成功")
        } else {
            Log.w(TAG, "Rime 引擎初始化失败: ${RimeJni.unavailableMessage()}")
        }
        return success
    }

    override fun shutdown() {
        RimeJni.shutdown()
        session?.let {
            try {
                RimeJni.destroySession(it)
            } catch (_: Exception) {}
        }
        session = null
        isInitialized = false
    }

    // --- 会话管理 ---

    override fun createSession(): RimeSession {
        val s = RimeJni.createSession()
        session = s
        return s
    }

    override fun destroySession(session: RimeSession) {
        RimeJni.destroySession(session)
        if (this.session?.id == session.id) {
            this.session = null
        }
    }

    // --- 输入处理 ---

    override fun processKey(key: String): Boolean {
        val s = session ?: createSession()
        return s.processKey(key)
    }

    override fun processKey(c: Char): Boolean {
        return processKey(c.toString())
    }

    override fun processKeyCode(keyCode: Int): Boolean {
        val s = session ?: createSession()
        return s.processKeyCode(keyCode)
    }

    override fun selectCandidate(index: Int): String {
        val s = session ?: return ""
        val result = s.selectCandidate(index)
        return result
    }

    override fun commit(): String {
        val s = session ?: return ""
        return s.commit()
    }

    override fun clear() {
        session?.clear()
    }

    override fun nextPage(): List<String> {
        session?.nextPage()
        return candidates
    }

    override fun prevPage(): List<String> {
        session?.prevPage()
        return candidates
    }
}
