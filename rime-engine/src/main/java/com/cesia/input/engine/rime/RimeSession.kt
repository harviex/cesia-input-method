package com.cesia.input.engine.rime

/**
 * Rime 输入会话
 * 封装一个活跃的 RIME 输入上下文，每个输入框对应一个独立 Session
 */
class RimeSession(val id: Long) {

    /**
     * 获取当前正在组合的文本（拼音串）
     */
    val composingText: String
        get() = RimeJni.getComposingText(id)

    /**
     * 是否有正在组合的文本
     */
    fun hasComposing(): Boolean = composingText.isNotEmpty()

    /**
     * 获取当前候选词列表
     */
    val candidates: List<String>
        get() = RimeJni.getCandidates(id)

    /** 候选词拼音列表（spelling hint），用于 T9 逐键选音按首字母过滤 */
    val candidatePinyins: List<String>
        get() = RimeJni.getCandidatePinyinList(id)

    /**
     * 是否有候选词
     */
    fun hasCandidates(): Boolean = candidates.isNotEmpty()

    /**
     * 处理按键输入
     * @param key 按键字符（字母/数字/符号）
     * @return true 表示有上下文更新
     */
    fun processKey(key: String): Boolean {
        return RimeJni.processKey(id, key)
    }

    /**
     * 处理按键输入（字符形式）
     */
    fun processKey(c: Char): Boolean {
        return RimeJni.processKey(id, c.toString())
    }

    /**
     * 处理功能键
     */
    fun processKeyCode(keyCode: Int): Boolean {
        return when (keyCode) {
            -5, android.view.KeyEvent.KEYCODE_BACK -> { // Backspace
                RimeJni.processKey(id, "BackSpace")
            }
            android.view.KeyEvent.KEYCODE_DEL -> {
                RimeJni.processKey(id, "BackSpace")
            }
            10, android.view.KeyEvent.KEYCODE_ENTER -> { // Enter
                commit()
                true
            }
            32, android.view.KeyEvent.KEYCODE_SPACE -> { // Space
                selectCandidate(0)
                true
            }
            else -> {
                val label = android.view.KeyEvent.keyCodeToString(keyCode)
                RimeJni.processKey(id, label)
            }
        }
    }

    /**
     * 提交当前组合文本（上屏）
     * @return 提交的文本
     */
    fun commit(): String {
        return RimeJni.commitComposition(id)
    }

    /**
     * 选择指定索引的候选词并上屏
     * @param index 候选词索引
     * @return 选中的候选词文本
     */
    fun selectCandidate(index: Int): String {
        if (index < 0 || index >= candidates.size) return ""
        return RimeJni.selectCandidate(id, index)
    }

    /**
     * 清除当前组合状态
     */
    fun clear() {
        RimeJni.clearComposition(id)
    }

    /**
     * 翻到下一页候选词
     */
    fun nextPage(): Boolean {
        return RimeJni.changePage(id, false)
    }

    /**
     * 翻到上一页候选词
     */
    fun prevPage(): Boolean {
        return RimeJni.changePage(id, true)
    }

    /**
     * 获取总页数
     */
    val pageCount: Int
        get() = RimeJni.getPageCount(id)

    /**
     * 获取当前页码（0-based）
     */
    val currentPage: Int
        get() = RimeJni.getCurrentPage(id)
}
