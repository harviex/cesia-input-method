package com.cesia.input

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.cesia.input.voice.VoiceEngine
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText

/**
 * 个性化设置页面
 * 支持左右滑动切换多个子页面
 */
class PersonalizationActivity : AppCompatActivity() {

    companion object {
        const val TAG = "CesiaPersonalization"
        const val PREFS_NAME = "cesia_commands"

        // 命令词 key
        const val KEY_CMD_EXIT = "cmd_exit"
        const val KEY_CMD_POLISH = "cmd_polish"
        const val KEY_CMD_FINISH = "cmd_finish"
        const val KEY_CMD_SEND = "cmd_send"
        const val KEY_CMD_COMMAND = "cmd_command"

        // 默认值
        const val DEFAULT_EXIT = "退出"
        const val DEFAULT_POLISH = "魔法"
        const val DEFAULT_FINISH = "结束"
        const val DEFAULT_SEND = "发送"
        const val DEFAULT_COMMAND = "指令"
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personalization)
        setTitle("🎨 个性化设置")

        // 启动时从 SharedPreferences 加载命令词到 VoiceEngine
        loadCommandWordsToVoiceEngine()

        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        val viewPager = findViewById<ViewPager2>(R.id.view_pager)

        viewPager.adapter = PersonalizationPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "语音命令"
                1 -> "外观"
                else -> "Tab $position"
            }
        }.attach()
    }

    private fun loadCommandWordsToVoiceEngine() {
        VoiceEngine.updateCommandWords(
            prefs.getString(KEY_CMD_EXIT, DEFAULT_EXIT) ?: DEFAULT_EXIT,
            prefs.getString(KEY_CMD_POLISH, DEFAULT_POLISH) ?: DEFAULT_POLISH,
            prefs.getString(KEY_CMD_FINISH, DEFAULT_FINISH) ?: DEFAULT_FINISH,
            prefs.getString(KEY_CMD_SEND, DEFAULT_SEND) ?: DEFAULT_SEND,
            prefs.getString(KEY_CMD_COMMAND, DEFAULT_COMMAND) ?: DEFAULT_COMMAND
        )
    }

    class PersonalizationPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> CommandWordsFragment()
                1 -> AppearanceFragment()
                else -> CommandWordsFragment()
            }
        }
    }

    /**
     * 语音命令词 Fragment
     */
    class CommandWordsFragment : Fragment(R.layout.fragment_command_words) {

        private lateinit var etExit: TextInputEditText
        private lateinit var etPolish: TextInputEditText
        private lateinit var etFinish: TextInputEditText
        private lateinit var etSend: TextInputEditText
        private lateinit var etCommand: TextInputEditText
        private lateinit var btnSave: Button
        private lateinit var btnReset: Button
        private lateinit var tvStatus: TextView

        private val prefs: SharedPreferences
            get() = requireContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            etExit = view.findViewById(R.id.et_cmd_exit)
            etPolish = view.findViewById(R.id.et_cmd_polish)
            etFinish = view.findViewById(R.id.et_cmd_finish)
            etSend = view.findViewById(R.id.et_cmd_send)
            btnSave = view.findViewById(R.id.btn_save_commands)
            btnReset = view.findViewById(R.id.btn_reset_commands)
            tvStatus = view.findViewById(R.id.tv_command_status)

            // 加载已保存的命令词
            etExit.setText(prefs.getString(KEY_CMD_EXIT, DEFAULT_EXIT))
            etPolish.setText(prefs.getString(KEY_CMD_POLISH, DEFAULT_POLISH))
            etFinish.setText(prefs.getString(KEY_CMD_FINISH, DEFAULT_FINISH))
            etSend.setText(prefs.getString(KEY_CMD_SEND, DEFAULT_SEND))
            etCommand = view.findViewById(R.id.et_cmd_command)
            etCommand.setText(prefs.getString(KEY_CMD_COMMAND, DEFAULT_COMMAND))

            btnSave.setOnClickListener { saveCommandWords() }
            btnReset.setOnClickListener { resetCommandWords() }
        }

        private fun saveCommandWords() {
            val exit = etExit.text?.toString()?.trim() ?: ""
            val polish = etPolish.text?.toString()?.trim() ?: ""
            val finish = etFinish.text?.toString()?.trim() ?: ""
            val send = etSend.text?.toString()?.trim() ?: ""
            val command = etCommand.text?.toString()?.trim() ?: ""

            // 校验
            val errors = mutableListOf<String>()
            if (exit.isEmpty()) errors.add("退出命令词不能为空")
            if (polish.isEmpty()) errors.add("润色命令词不能为空")
            if (finish.isEmpty()) errors.add("结束命令词不能为空")
            if (send.isEmpty()) errors.add("发送命令词不能为空")
            if (command.isEmpty()) errors.add("指令模式词不能为空")

            if (exit.length > 5) errors.add("退出命令词超过 5 个字")
            if (polish.length > 5) errors.add("润色命令词超过 5 个字")
            if (finish.length > 5) errors.add("结束命令词超过 5 个字")
            if (send.length > 5) errors.add("发送命令词超过 5 个字")
            if (command.length > 5) errors.add("指令模式词超过 5 个字")

            // 检查重复
            val words = listOf(exit, polish, finish, send, command)
            val duplicates = words.groupBy { it }.filter { it.value.size > 1 }.keys
            if (duplicates.isNotEmpty()) {
                errors.add("命令词不能重复：${duplicates.joinToString("、")}")
            }

            if (errors.isNotEmpty()) {
                tvStatus.text = "❌ ${errors.joinToString("\n")}"
                tvStatus.setBackgroundColor(0xFFFFEBEE.toInt())
                return
            }

            // 保存到 SharedPreferences
            prefs.edit()
                .putString(KEY_CMD_EXIT, exit)
                .putString(KEY_CMD_POLISH, polish)
                .putString(KEY_CMD_FINISH, finish)
                .putString(KEY_CMD_SEND, send)
                .putString(KEY_CMD_COMMAND, command)
                .apply()

            // 立即更新 VoiceEngine
            VoiceEngine.updateCommandWords(exit, polish, finish, send, command)

            tvStatus.text = "✅ 已保存并生效：退出=$exit, 润色=$polish, 结束=$finish, 发送=$send, 指令=$command"
            tvStatus.setBackgroundColor(0xFFE8F5E9.toInt())
            Log.i(TAG, "命令词已更新: exit=$exit, polish=$polish, finish=$finish, send=$send, command=$command")

            Toast.makeText(requireContext(), "命令词已保存", Toast.LENGTH_SHORT).show()
        }

        private fun resetCommandWords() {
            etExit.setText(DEFAULT_EXIT)
            etPolish.setText(DEFAULT_POLISH)
            etFinish.setText(DEFAULT_FINISH)
            etSend.setText(DEFAULT_SEND)
            etCommand.setText(DEFAULT_COMMAND)
            tvStatus.text = "已恢复默认值，请点击保存"
            tvStatus.setBackgroundColor(0xFFFFF3E0.toInt())
        }
    }

    /**
     * 外观设置 Fragment（占位，后续扩展）
     */
    class AppearanceFragment : Fragment(R.layout.fragment_appearance)
}
