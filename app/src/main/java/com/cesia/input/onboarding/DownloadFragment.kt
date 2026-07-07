package com.cesia.input.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.cesia.input.R
import com.cesia.input.model.ModelDownloadManager
import kotlinx.coroutines.launch

class DownloadFragment : Fragment(R.layout.fragment_download) {

    private lateinit var viewModel: OnboardingViewModel
    private var isDownloading = false

    // Views
    private var tvFeatureIntro: TextView? = null
    private var tvStepIndicator: TextView? = null
    private var llDict: LinearLayout? = null
    private var pgDict: ProgressBar? = null
    private var tvDictProgress: TextView? = null
    private var tvDictStatus: TextView? = null
    private var llVoice: LinearLayout? = null
    private var cbVoice: CheckBox? = null
    private var pgVoice: ProgressBar? = null
    private var tvVoiceProgress: TextView? = null
    private var tvVoiceStatus: TextView? = null
    private var llAi: LinearLayout? = null
    private var cbAi: CheckBox? = null
    private var pgAi: ProgressBar? = null
    private var tvAiProgress: TextView? = null
    private var tvAiStatus: TextView? = null
    private var btnPrev: Button? = null
    private var btnStartDownload: Button? = null
    private var btnNext: Button? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[OnboardingViewModel::class.java]

        bindViews(view)

        // 顶部功能介绍（下载等待时阅读）
        tvFeatureIntro?.text = """
            🎤 语音输入：长按语音键说话，松开自动识别；再次长按切换本地/云端
            ✍️ 智能写作：说出「写作」+ 主题，AI 自动生成文案（邮件/周报/文案/代码）
            🔧 智能修改：选中文字 → 语音说「改成更正式/更简洁/翻译成英文」，AI 原地重写
            📤 清空/发送：语音说「清空」清空输入框，「发送」直接按 Enter 键发送
        """.trimIndent()

        // 勾选框：词库必选（隐藏勾选框，只显示文案）
        cbVoice?.isChecked = viewModel.downloadVoice
        cbAi?.isChecked = viewModel.downloadAi
        cbVoice?.setOnCheckedChangeListener { _, checked -> viewModel.downloadVoice = checked }
        cbAi?.setOnCheckedChangeListener { _, checked -> viewModel.downloadAi = checked }

        // 进度观察
        observeProgress(viewModel.dictProgress, pgDict, tvDictProgress, tvDictStatus)
        observeProgress(viewModel.voiceProgress, pgVoice, tvVoiceProgress, tvVoiceStatus)
        observeProgress(viewModel.aiProgress, pgAi, tvAiProgress, tvAiStatus)

        // 完成监听：词库完成且（未勾选模型 或 模型也完成）→ 显示下一步按钮
        viewModel.dictCompleted.observe(viewLifecycleOwner) { success ->
            if (success) checkAllDone()
        }
        viewModel.voiceCompleted.observe(viewLifecycleOwner) { _ -> checkAllDone() }
        viewModel.aiCompleted.observe(viewLifecycleOwner) { _ -> checkAllDone() }

        btnStartDownload?.setOnClickListener {
            if (!isDownloading) startDownloads()
        }
        btnPrev?.setOnClickListener {
            (activity as? OnboardingActivity)?.goPrevious()
        }
        btnNext?.setOnClickListener {
            (activity as? OnboardingActivity)?.goNext()
        }
    }

    private fun bindViews(view: View) {
        tvFeatureIntro = view.findViewById(R.id.tvFeatureIntro)
        tvStepIndicator = view.findViewById(R.id.tvStepIndicator)
        llDict = view.findViewById(R.id.llDict)
        pgDict = view.findViewById(R.id.pgDict)
        tvDictProgress = view.findViewById(R.id.tvDictProgress)
        tvDictStatus = view.findViewById(R.id.tvDictStatus)
        llVoice = view.findViewById(R.id.llVoice)
        cbVoice = view.findViewById(R.id.cbVoice)
        pgVoice = view.findViewById(R.id.pgVoice)
        tvVoiceProgress = view.findViewById(R.id.tvVoiceProgress)
        tvVoiceStatus = view.findViewById(R.id.tvVoiceStatus)
        llAi = view.findViewById(R.id.llAi)
        cbAi = view.findViewById(R.id.cbAi)
        pgAi = view.findViewById(R.id.pgAi)
        tvAiProgress = view.findViewById(R.id.tvAiProgress)
        tvAiStatus = view.findViewById(R.id.tvAiStatus)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnStartDownload = view.findViewById(R.id.btnStartDownload)
        btnNext = view.findViewById(R.id.btnNext)
    }

    private fun startDownloads() {
        isDownloading = true
        btnStartDownload?.visibility = View.GONE
        btnPrev?.visibility = View.GONE
        btnNext?.visibility = View.GONE
        viewModel.startDownloads()
    }

    private fun checkAllDone() {
        val dictDone = viewModel.dictCompleted.value == true
        val voiceDone = !viewModel.downloadVoice || viewModel.voiceCompleted.value == true
        val aiDone = !viewModel.downloadAi || viewModel.aiCompleted.value == true
        if (dictDone && voiceDone && aiDone) {
            lifecycleScope.launch {
                btnNext?.visibility = View.VISIBLE
                btnPrev?.visibility = View.VISIBLE
            }
        }
    }

    private fun observeProgress(
        liveData: androidx.lifecycle.LiveData<OnboardingViewModel.DownloadProgress>,
        progressBar: ProgressBar?,
        tvProgress: TextView?,
        tvStatus: TextView?
    ) {
        liveData.observe(viewLifecycleOwner) { prog ->
            progressBar?.progress = prog.percent
            tvProgress?.text = "${prog.percent}%"
            tvStatus?.text = prog.status
            // 显示步骤指示
            val currentStep = when (prog.item) {
                OnboardingViewModel.DownloadItem.DICT -> 1
                OnboardingViewModel.DownloadItem.VOICE -> 2
                OnboardingViewModel.DownloadItem.AI -> 3
            }
            tvStepIndicator?.text = "步骤 $currentStep / 3"
            if (prog.percent >= 100) {
                progressBar?.progress = 100
            }
        }
    }
}