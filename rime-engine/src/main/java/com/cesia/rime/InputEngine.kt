package com.cesia.rime

/**
 * 输入引擎统一接口
 * 所有输入引擎（Rime、本地模型等）都应实现此接口
 */
interface InputEngine {

    /**
     * 初始化引擎
     */
    fun initialize(config: EngineConfig): Boolean

    /**
     * 创建新的输入会话
     */
    fun createSession(): SessionHandle

    /**
     * 处理按键输入
     */
    fun processKey(session: SessionHandle, keyCode: Int, mask: Int = 0): ProcessResult

    /**
     * 获取候选词列表
     */
    fun getCandidates(session: SessionHandle, limit: Int = 10): List<Candidate>

    /**
     * 提交当前输入
     */
    fun commit(session: SessionHandle): String?

    /**
     * 清空当前会话
     */
    fun clear(session: SessionHandle)

    /**
     * 选择候选词
     */
    fun selectCandidate(session: SessionHandle, index: Int): Boolean

    /**
     * 切换输入方案
     */
    fun switchSchema(session: SessionHandle, schemaId: String): Boolean

    /**
     * 销毁会话
     */
    fun destroySession(session: SessionHandle)

    /**
     * 释放引擎资源
     */
    fun release()
}

/**
 * 引擎配置
 */
data class EngineConfig(
    val sharedDataDir: String,
    val userDataDir: String,
    val logDir: String? = null
)

/**
 * 会话句柄
 */
data class SessionHandle(val id: Long)

/**
 * 候选词
 */
data class Candidate(
    val text: String,
    val comment: String? = null,
    val label: String? = null
)

/**
 * 按键处理结果
 */
sealed class ProcessResult {
    object Accepted : ProcessResult()
    object Rejected : ProcessResult()
    data class Commit(val text: String) : ProcessResult()
}