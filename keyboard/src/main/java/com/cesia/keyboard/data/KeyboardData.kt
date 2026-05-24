package com.cesia.keyboard.data

/**
 * 键盘类型枚举
 */
enum class KeyboardType {
    /** 全键盘 QWERTY */
    QWERTY,
    /** T9 键盘 */
    T9,
    /** 英文符号 */
    SYMBOLS_EN,
    /** 中文符号 */
    SYMBOLS_CN
}

/**
 * 按键类型枚举
 */
enum class KeyType {
    /** 普通字符键 */
    CHARACTER,
    /** 功能键（Shift、删除等） */
    FUNCTION,
    /** 空格键 */
    SPACE,
    /** 回车/发送键 */
    ENTER,
    /** 键盘切换 */
    KEYBOARD_SWITCH,
    /** 符号切换 */
    SYMBOL_SWITCH,
    /** 语言切换 */
    LANG_SWITCH,
    /** 退格 */
    BACKSPACE,
    /** 清空 */
    CLEAR,
    /** 剪贴板 */
    CLIPBOARD
}

/**
 * 键盘数据类
 * 对应 JSON 配置中的键盘定义
 */
data class Keyboard(
    /** 键盘唯一标识 */
    val id: String,
    /** 键盘类型 */
    val type: KeyboardType,
    /** 键盘行列表 */
    val rows: List<KeyRow>,
    /** 按键宽度百分比（默认 10%） */
    val keyWidthPercent: Float = 10f,
    /** 按键高度（dp） */
    val keyHeightDp: Float = 44f,
    /** 水平间距（dp） */
    val horizontalGapDp: Float = 2f,
    /** 垂直间距（dp） */
    val verticalGapDp: Float = 4f
)

/**
 * 键盘行
 */
data class KeyRow(
    val keys: List<Key>
)

/**
 * 按键数据类
 */
data class Key(
    /** 按键标签（显示文字） */
    val label: String,
    /** 输出的字符码列表（第一个为主任务） */
    val codes: List<Int>,
    /** 长按弹出的副字符 */
    val popupCharacters: String? = null,
    /** 按键宽度百分比（相对于键盘宽度） */
    val widthPercent: Float? = null,
    /** 按键高度（dp） */
    val heightDp: Float? = null,
    /** 按键类型 */
    val keyType: KeyType = KeyType.CHARACTER,
    /** 水平间距（dp） */
    val horizontalGapDp: Float? = null,
    /** 是否左侧边缘键 */
    val isLeftEdge: Boolean = false,
    /** 是否右侧边缘键 */
    val isRightEdge: Boolean = false,
    /** 图标资源名（可选，优先于 label 显示） */
    val iconName: String? = null,
    /** 是否重复触发（如退格键长按连续删除） */
    val repeatable: Boolean = false
) {
    /** 获取主字符码 */
    val primaryCode: Int get() = codes.firstOrNull() ?: 0

    /** 获取主标签（大写模式时返回大写） */
    fun getDisplayLabel(capsMode: Boolean = false): String {
        return if (capsMode && label.length == 1 && label[0].isLetter()) {
            label.uppercase()
        } else {
            label
        }
    }
}
