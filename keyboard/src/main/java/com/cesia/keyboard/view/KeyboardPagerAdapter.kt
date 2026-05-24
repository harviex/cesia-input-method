package com.cesia.keyboard.view

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.cesia.keyboard.data.Key
import com.cesia.keyboard.data.KeyType

/**
 * 键盘页面适配器
 * 用于 ViewPager2 管理三个页面：
 * - 第 0 页：主功能页
 * - 第 1 页：T9 键盘
 * - 第 2 页：全键盘（QWERTY）
 */
class KeyboardPagerAdapter(
    private val context: Context,
    private val keyboardWindow: KeyboardWindow
) : RecyclerView.Adapter<KeyboardPagerAdapter.PageViewHolder>() {

    companion object {
        const val PAGE_FUNCTION = 0
        const val PAGE_T9 = 1
        const val PAGE_QWERTY = 2
        const val PAGE_COUNT = 3
    }

    var onFunctionButtonClickListener: ((String) -> Unit)? = null

    override fun getItemCount(): Int = PAGE_COUNT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = when (viewType) {
            PAGE_FUNCTION -> createFunctionPage(parent)
            PAGE_T9 -> createT9Page(parent)
            PAGE_QWERTY -> createQwertyPage(parent)
            else -> createFunctionPage(parent)
        }
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // 页面内容已在创建时绑定
    }

    override fun getItemViewType(position: Int): Int = position

    // ===== 主功能页 =====

    private fun createFunctionPage(parent: ViewGroup): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // 标题行
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val titleLabel = android.widget.TextView(context).apply {
            text = "Cesia 工具箱"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleRow.addView(titleLabel)
        layout.addView(titleRow)

        // 功能按钮网格
        val buttons = listOf(
            FunctionButton("🎤", "语音", "voice"),
            FunctionButton("✨", "魔法修改", "magic"),
            FunctionButton("✍️", "自动写作", "auto_write"),
            FunctionButton("🗑️", "清空", "clear"),
            FunctionButton("⚙️", "设置", "settings"),
            FunctionButton("📋", "剪贴板", "clipboard")
        )

        // 每行 3 个按钮
        var currentRow: LinearLayout? = null
        for ((index, button) in buttons.withIndex()) {
            if (index % 3 == 0) {
                currentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) topMargin = dp(6)
                        bottomMargin = dp(6)
                    }
                }
                layout.addView(currentRow)
            }

            val btnView = createFunctionButton(button)
            currentRow?.addView(btnView)
        }

        return layout
    }

    private fun createFunctionButton(button: FunctionButton): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val iconView = android.widget.TextView(context).apply {
            text = button.icon
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                bottomMargin = dp(4)
            }
            background = createRoundDrawable(0xFFE8E8E8.toInt(), dp(12f))
            setTextColor(0xFF555555.toInt())
        }

        val labelView = android.widget.TextView(context).apply {
            text = button.label
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(iconView)
        container.addView(labelView)

        // 点击事件
        container.setOnClickListener {
            onFunctionButtonClickListener?.invoke(button.action)
        }

        return container
    }

    // ===== T9 键盘页 =====

    private fun createT9Page(parent: ViewGroup): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 使用 KeyboardWindow 加载 T9 键盘
        val t9Window = KeyboardWindow(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(t9Window)
        return layout
    }

    // ===== QWERTY 键盘页 =====

    private fun createQwertyPage(parent: ViewGroup): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 使用 KeyboardWindow 加载 QWERTY 键盘
        val qwertyWindow = KeyboardWindow(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(qwertyWindow)
        return layout
    }

    // ===== 辅助方法 =====

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun dp(value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    private fun createRoundDrawable(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    // ===== 数据类 =====

    private data class FunctionButton(
        val icon: String,
        val label: String,
        val action: String
    )

    // ===== ViewHolder =====

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
