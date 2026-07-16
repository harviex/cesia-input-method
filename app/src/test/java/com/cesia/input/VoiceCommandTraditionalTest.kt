package com.cesia.input

import com.cesia.input.voice.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语音命令词检测的正体兼容性回归测试：
 * 验证「识别为繁体时，简体命令词(如"写作")仍能触发并被剥离」，
 * 且剥离后保留原文繁体其余部分、命令词本身被去除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCommandTraditionalTest {

    private fun ctx() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun engine(): VoiceEngine {
        val e = VoiceEngine(ctx())
        VoiceEngine.updateCommandWords(
            exit = "退出", polish = "润色", finish = "结束",
            send = "发送", command = "指令", writing = "写作",
            undo = "撤销", clear = "清空", restore = "恢复"
        )
        return e
    }

    @Test
    fun `简体识别-简体命令词正常剥离`() {
        OpenCCConverter.load(ctx().assets)
        val r = engine().checkCommandWord("今天天气不错 写作")
        assertEquals("今天天气不错", r?.first)
        assertEquals("writing", r?.second)
    }

    @Test
    fun `繁体识别-简体命令词仍触发且保留繁体正文`() {
        OpenCCConverter.load(ctx().assets)
        // 识别为繁体："今天天氣不錯 寫作"，命令词存简体"写作"
        val r = engine().checkCommandWord("今天天氣不錯 寫作")
        assertEquals("今天天氣不錯", r?.first)   // 命令词被剥离，繁体正文保留
        assertEquals("writing", r?.second)
    }

    @Test
    fun `繁体识别-退出命令词`() {
        OpenCCConverter.load(ctx().assets)
        val r = engine().checkCommandWord("會議記錄 退出")
        assertEquals("會議記錄", r?.first)
        assertEquals("exit", r?.second)
    }

    @Test
    fun `繁体识别-发送命令词`() {
        OpenCCConverter.load(ctx().assets)
        val r = engine().checkCommandWord("這是訂單號 發送")
        assertEquals("這是訂單號", r?.first)
        assertEquals("send", r?.second)
    }

    @Test
    fun `无命令词-返回null`() {
        OpenCCConverter.load(ctx().assets)
        assertNull(engine().checkCommandWord("今天天氣不錯純內容"))
    }

    @Test
    fun `繁体命令词在句首不算`() {
        OpenCCConverter.load(ctx().assets)
        assertNull(engine().checkCommandWord("寫作 今天天氣不錯"))
    }
}
