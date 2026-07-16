package com.cesia.input

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

/**
 * OpenCC 简繁转换单元测试（需 Robolectric 提供 AssetManager 加载 opencc_s2t.json）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenCCConverterTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `简转繁-常用字`() {
        OpenCCConverter.load(ctx().assets)
        assertEquals("繁體", OpenCCConverter.toTraditional("繁体"))
        assertEquals("中華人民共和國", OpenCCConverter.toTraditional("中华人民共和国"))
    }

    @Test
    fun `繁转简-常用字`() {
        OpenCCConverter.load(ctx().assets)
        assertEquals("繁体", OpenCCConverter.toSimplified("繁體"))
        assertEquals("中华人民共和国", OpenCCConverter.toSimplified("中華人民共和國"))
    }

    @Test
    fun `繁转简-与简转繁互逆(单字)`() {
        OpenCCConverter.load(ctx().assets)
        val trad = OpenCCConverter.toTraditional("软件")
        assertEquals("软件", OpenCCConverter.toSimplified(trad))
    }

    @Test
    fun `空串不变`() {
        OpenCCConverter.load(ctx().assets)
        assertEquals("", OpenCCConverter.toTraditional(""))
        assertEquals("", OpenCCConverter.toSimplified(""))
    }
}
