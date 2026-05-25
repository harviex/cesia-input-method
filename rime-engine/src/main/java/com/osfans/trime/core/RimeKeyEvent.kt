package com.osfans.trime.core

/**
 * Rime 按键事件（JNI 回调用）
 */
data class RimeKeyEvent(
    val keycode: Int,
    val modifier: Int,
    val name: String = "",
)
