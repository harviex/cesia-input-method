package com.cesia.input

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import com.cesia.input.voice.VoiceEngine

/**
 * VoiceEngine.convertChineseDigitsToArabic 单元测试
 * 覆盖：含单位数值、两字以上连续数字、单字保持、序数不转
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChineseDigitsTest {

    private val engine = VoiceEngine(ApplicationProvider.getApplicationContext())

    @Test
    fun `含单位数字转阿拉伯`() {
        assertEquals("123", engine.convertChineseDigitsToArabic("一百二十三"))
        assertEquals("2024年", engine.convertChineseDigitsToArabic("二零二四年"))
    }

    @Test
    fun `两字以上连续中文数字转阿拉伯`() {
        assertEquals("435", engine.convertChineseDigitsToArabic("四三五"))
    }

    @Test
    fun `单个中文数字保持汉字`() {
        assertEquals("五", engine.convertChineseDigitsToArabic("五"))
        assertEquals("三", engine.convertChineseDigitsToArabic("三"))
    }

    @Test
    fun `序数前缀不转换`() {
        assertEquals("第一次", engine.convertChineseDigitsToArabic("第一次"))
        assertEquals("第三次", engine.convertChineseDigitsToArabic("第三次"))
    }

    @Test
    fun `命令词场景-数字保留阿拉伯`() {
        // 模拟“一百二十三 上屏”：含单位应转阿拉伯，且非序数
        assertEquals("123 上屏", engine.convertChineseDigitsToArabic("一百二十三 上屏"))
    }
}
