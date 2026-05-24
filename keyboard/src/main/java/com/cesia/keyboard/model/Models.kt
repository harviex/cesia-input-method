package com.cesia.keyboard.model

/**
 * 键盘类型枚举
 */
enum class KeyboardType {
    QWERTY,         // 全键盘
    T9,             // 9宫格
    SYMBOLS_EN,     // 英文符号
    SYMBOLS_CN,     // 中文符号
    FUNCTION        // 功能键
}

/**
 * 按键类型枚举
 */
enum class KeyType {
    CHARACTER,      // 字符键
    DIGIT,          // 数字键
    SYMBOL,         // 符号键
    SPACE,          // 空格键
    ENTER,          // 回车键
    BACKSPACE,      // 删除键
    SHIFT,          //  Shift键
    SYMBOL_SWITCH,  // 符号切换键
    LANG_SWITCH,    // 语言切换键
    COMMA,          // 逗号
    PERIOD,         // 句号
    KEYBOARD_SWITCH,// 键盘切换键
    CLIPBOARD,      // 剪贴板键
    CLEAR,          // 清除键
    SEND            // 发送键
}

/**
 * 按键数据类
 */
data class Key(
    val label: String = "",
    val codes: IntArray = intArrayOf(),
    val popupCharacters: String? = null,
    val width: Int = 0,      // dp
    val height: Int = 0,     // dp
    val type: KeyType = KeyType.CHARACTER,
    val isRepeatable: Boolean = false,
    val marginLeft: Int = 0,
    val marginTop: Int = 0,
    val marginBottom: Int = 0,
    val sticky: Boolean = false,
    val keyColor: Int? = null,
    val keyTextColor: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Key) return false
        return label == other.label && codes.contentEquals(other.codes)
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + codes.contentHashCode()
        return result
    }
}

/**
 * 键盘数据类
 */
data class Keyboard(
    val id: String,
    val type: KeyboardType,
    val rows: List<List<Key>>,
    val keyWidth: Int = 36,
    val keyHeight: Int = 48,
    val horizontalGap: Int = 2,
    val verticalGap: Int = 4,
    val isShifted: Boolean = false
) {
    /**
     * 获取所有按键（展平）
     */
    val keys: List<Key>
        get() = rows.flatten()
}