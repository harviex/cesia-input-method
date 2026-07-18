package com.cesia.input

import android.graphics.drawable.GradientDrawable
import android.content.res.Resources

/**
 * 颜色与主题相关纯函数工具（无状态，不依赖服务实例）。
 */
object ColorUtils {

    /** HSL → ARGB 颜色值（不透明，alpha=0xFF） */
    fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (ri shl 16) or (gi shl 8) or bi
    }

    /**
     * 生成按键背景圆角矩形 Drawable（带比底色略深的描边）。
     * density 用于按屏幕密度缩放圆角与描边。
     */
    fun makeKeyBgDrawable(keyBgColor: Int, density: Float): GradientDrawable {
        // 判断是否灰度色（R==G==B）：键盘/底栏常态为灰底，边取极淡灰(keyBg-16)，
        // 亮色主题下几乎不可见，即“像键盘按钮那种边框效果”；
        // 若是彩色高亮（如主题色发光态），则用固定浅灰描边，避免 Tiffany 蓝边框。
        val r = (keyBgColor shr 16) and 0xFF
        val g = (keyBgColor shr 8) and 0xFF
        val b = keyBgColor and 0xFF
        val isGray = (r == g && g == b)
        val strokeColor = if (isGray) {
            val strokeGray = (r - 16).coerceIn(0, 255)
            0xFF000000.toInt() or (strokeGray shl 16) or (strokeGray shl 8) or strokeGray
        } else {
            0xFFCCCCCC.toInt()
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(keyBgColor)
            cornerRadius = 6f * density
            setStroke((1 * density).toInt(), strokeColor)
        }
    }

    /**
     * 随手机时间自动变化主题色：根据当前小时(0-23)计算色相。
     * 一天从早到晚：黎明暖橙(约40°)→上午明黄绿(约90°)→正午青蓝(蒂芙尼180°)→
     * 黄昏暖紫(约300°)→深夜冷蓝(约220°)，形成从早到晚的渐变循环。
     */
    fun timeBasedHue(): Float {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val t = (h * 60 + m) / 1440f // 0.0(00:00) ~ 1.0(24:00)
        val hue = 180f + 140f * kotlin.math.sin((t - 0.5f) * 2 * Math.PI.toFloat())
        var hh = hue % 360f
        if (hh < 0) hh += 360f
        return hh
    }
}
