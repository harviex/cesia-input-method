package com.cesia.input.t9

import android.content.Context
import android.content.SharedPreferences

/**
 * 候选词偏好：置顶（加权）与降级（降权），全局持久化。
 *
 * 存储结构（SharedPreferences "cesia_cand_prefs"）：
 * - "pin:<词>" -> "top"     置顶（优先显示）
 * - "down:<词>" -> "1"      降级（退到候选区后列）
 *
 * 应用方式：在 updateCandidateBar 后，对候选列表做重排：
 *   置顶词排最前（保持原序），其余按 Rime 词频，降级词排最后。
 */
object CandidatePrefs {

    private const val SP_NAME = "cesia_cand_prefs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun isPinned(ctx: Context, word: String): Boolean =
        sp(ctx).getString("pin:$word", null) == "top"

    fun isDowngraded(ctx: Context, word: String): Boolean =
        sp(ctx).getBoolean("down:$word", false)

    fun pin(ctx: Context, word: String) {
        sp(ctx).edit()
            .putString("pin:$word", "top")
            .remove("down:$word")
            .apply()
    }

    fun downgrade(ctx: Context, word: String) {
        sp(ctx).edit()
            .putBoolean("down:$word", true)
            .remove("pin:$word")
            .apply()
    }

    fun reset(ctx: Context, word: String) {
        sp(ctx).edit()
            .remove("pin:$word")
            .remove("down:$word")
            .apply()
    }

    /**
     * 对 Rime 返回的候选词列表重排：
     * 置顶词 -> 最前（按置顶顺序即原列表顺序保留），
     * 降级词 -> 最后，
     * 其余保持 Rime 词频顺序。
     */
    fun reorder(ctx: Context, candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return candidates
        val pinned = candidates.filter { isPinned(ctx, it) }
        val normal = candidates.filter { !isPinned(ctx, it) && !isDowngraded(ctx, it) }
        val down = candidates.filter { isDowngraded(ctx, it) && !isPinned(ctx, it) }
        return pinned + normal + down
    }
}
