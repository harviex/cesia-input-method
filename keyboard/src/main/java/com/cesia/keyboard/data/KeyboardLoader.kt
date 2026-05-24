package com.cesia.keyboard.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

/**
 * JSON 键盘配置加载器
 * 从 assets/keyboards/ 目录加载 JSON 格式的键盘布局
 */
object KeyboardLoader {

    private val gson = Gson()
    private val cache = mutableMapOf<String, Keyboard>()

    /**
     * 从 assets 加载键盘配置
     * @param context Android 上下文
     * @param assetPath assets 下的路径，如 "keyboards/qwerty.json"
     * @return 解析后的 Keyboard 对象
     */
    fun loadFromAssets(context: Context, assetPath: String): Keyboard? {
        // 检查缓存
        cache[assetPath]?.let { return it }

        return try {
            val inputStream = context.assets.open(assetPath)
            val reader = InputStreamReader(inputStream, "UTF-8")
            val json = reader.readText()
            reader.close()
            inputStream.close()

            val keyboard = parseKeyboard(json)
            cache[assetPath] = keyboard
            keyboard
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 JSON 字符串解析键盘
     */
    fun parseKeyboard(json: String): Keyboard {
        val type = object : TypeToken<KeyboardConfig>() {}.type
        val config: KeyboardConfig = gson.fromJson(json, type)
        return config.toKeyboard()
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.clear()
    }

    // ===== 内部 JSON 数据结构 =====

    private data class KeyboardConfig(
        val id: String,
        val type: String,
        val keyWidthPercent: Float = 10f,
        val keyHeightDp: Float = 44f,
        val horizontalGapDp: Float = 2f,
        val verticalGapDp: Float = 4f,
        val rows: List<RowConfig>
    ) {
        fun toKeyboard(): Keyboard {
            val keyboardType = when (type.lowercase()) {
                "qwerty" -> KeyboardType.QWERTY
                "t9" -> KeyboardType.T9
                "symbols_en" -> KeyboardType.SYMBOLS_EN
                "symbols_cn" -> KeyboardType.SYMBOLS_CN
                else -> KeyboardType.QWERTY
            }

            val keyRows = rows.map { rowConfig ->
                KeyRow(keys = rowConfig.keys.map { it.toKey() })
            }

            return Keyboard(
                id = id,
                type = keyboardType,
                rows = keyRows,
                keyWidthPercent = keyWidthPercent,
                keyHeightDp = keyHeightDp,
                horizontalGapDp = horizontalGapDp,
                verticalGapDp = verticalGapDp
            )
        }
    }

    private data class RowConfig(
        val keys: List<KeyConfig>
    )

    private data class KeyConfig(
        val label: String,
        val codes: List<Int>? = null,
        val code: Int? = null,
        val popupCharacters: String? = null,
        val widthPercent: Float? = null,
        val heightDp: Float? = null,
        val keyType: String? = null,
        val horizontalGapDp: Float? = null,
        val isLeftEdge: Boolean = false,
        val isRightEdge: Boolean = false,
        val iconName: String? = null,
        val repeatable: Boolean = false
    ) {
        fun toKey(): Key {
            val actualCodes = codes ?: (code?.let { listOf(it) } ?: listOf(0))
            val actualKeyType = when (keyType?.lowercase()) {
                "character" -> KeyType.CHARACTER
                "function" -> KeyType.FUNCTION
                "space" -> KeyType.SPACE
                "enter" -> KeyType.ENTER
                "keyboard_switch" -> KeyType.KEYBOARD_SWITCH
                "symbol_switch" -> KeyType.SYMBOL_SWITCH
                "lang_switch" -> KeyType.LANG_SWITCH
                "backspace" -> KeyType.BACKSPACE
                "clear" -> KeyType.CLEAR
                "clipboard" -> KeyType.CLIPBOARD
                else -> KeyType.CHARACTER
            }

            return Key(
                label = label,
                codes = actualCodes,
                popupCharacters = popupCharacters,
                widthPercent = widthPercent,
                heightDp = heightDp,
                keyType = actualKeyType,
                horizontalGapDp = horizontalGapDp,
                isLeftEdge = isLeftEdge,
                isRightEdge = isRightEdge,
                iconName = iconName,
                repeatable = repeatable
            )
        }
    }
}
