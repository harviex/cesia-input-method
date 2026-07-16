package com.cesia.input

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ColorUtils 纯函数单元测试（JVM 可跑，无需 Android 资源）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorUtilsTest {

    @Test
    fun `hslToColor-红色`() {
        // HSL(0,1,0.5) -> 纯红 0xFFFF0000
        assertEquals(0xFFFF0000.toInt(), ColorUtils.hslToColor(0f, 1f, 0.5f))
    }

    @Test
    fun `hslToColor-绿色`() {
        // HSL(120,1,0.5) -> 纯绿 0xFF00FF00
        assertEquals(0xFF00FF00.toInt(), ColorUtils.hslToColor(120f, 1f, 0.5f))
    }

    @Test
    fun `hslToColor-蓝色`() {
        // HSL(240,1,0.5) -> 纯蓝 0xFF0000FF
        assertEquals(0xFF0000FF.toInt(), ColorUtils.hslToColor(240f, 1f, 0.5f))
    }

    @Test
    fun `hslToColor-蒂芙尼蓝约在180度`() {
        // 蒂芙尼约 (180,0.53,0.65)；只验证 alpha 不透明与值在合法范围内
        val c = ColorUtils.hslToColor(180f, 0.53f, 0.65f)
        assertEquals(0xFF000000.toInt(), c and 0xFF000000.toInt())
    }

    @Test
    fun `timeBasedHue-结果落在0到360`() {
        val h = ColorUtils.timeBasedHue()
        assert(h >= 0f && h < 360f) { "hue=$h 超出 [0,360)" }
    }
}
