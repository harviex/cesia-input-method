package com.cesia.input.engine.rime

/**
 * 输入引擎统一接口
 * 所有输入引擎（PinyinEngine、RimeEngine 等）都实现此接口
 */
interface InputEngine {

    /**
     * 引擎名称
     */
    val name: String

    /**
     * 引擎是否已初始化
     */
    val isInitialized: Boolean

    /**
     * 引擎是否可用（初始化成功且 native 库已加载）
     */
    val isAvailable: Boolean

    /**
     * 初始化引擎
     */
    fun initialize(): Boolean

    /**
     * 关闭引擎
     */
    fun shutdown()

    /**
     * 创建新的输入会话
     */
    fun createSession(): RimeSession

    /**
     * 销毁输入会话
     */
    fun destroySession(session: RimeSession)

    /**
     * 是否有正在组合的文本
     */
    val isComposing: Boolean

    /**
     * 获取当前组合文本
     */
    val composingText: String

    /**
     * 获取候选词列表
     */
    val candidates: List<String>

    /**
     * 是否有候选词
     */
    val hasCandidates: Boolean

    /**
     * 处理按键
     */
    fun processKey(key: String): Boolean

    /**
     * 处理按键（字符形式）
     */
    fun processKey(c: Char): Boolean

    /**
     * 处理功能键
     */
    fun processKeyCode(keyCode: Int): Boolean

    /**
     * 选择候选词
     */
    fun selectCandidate(index: Int): String

    /**
     * 提交当前组合
     */
    fun commit(): String

    /**
     * 清除组合状态
     */
    fun clear()

    /**
     * 翻页
     */
    fun nextPage(): List<String>
    fun prevPage(): List<String>

    /**
     * 获取页数信息
     */
    val pageCount: Int
    val currentPage: Int
}
