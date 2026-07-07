package com.cesia.input.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.cesia.input.R

class CloudSetupFragment : Fragment(R.layout.fragment_cloud_setup) {

    private lateinit var viewModel: OnboardingViewModel
    private var etOpenRouterKey: EditText? = null
    private var etTavilyKey: EditText? = null
    private var btnOpenRouterSite: Button? = null
    private var btnTavilySite: Button? = null
    private var rgNewsSources: RadioGroup? = null
    private var btnSaveCloud: Button? = null
    private var btnSkipCloud: Button? = null
    private var btnPrev: Button? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[OnboardingViewModel::class.java]

        etOpenRouterKey = view.findViewById(R.id.etOpenRouterKey)
        etTavilyKey = view.findViewById(R.id.etTavilyKey)
        btnOpenRouterSite = view.findViewById(R.id.btnOpenRouterSite)
        btnTavilySite = view.findViewById(R.id.btnTavilySite)
        rgNewsSources = view.findViewById(R.id.rgNewsSources)
        btnSaveCloud = view.findViewById(R.id.btnSaveCloud)
        btnSkipCloud = view.findViewById(R.id.btnSkipCloud)
        btnPrev = view.findViewById(R.id.btnPrev)

        // 预填已保存的 Key
        val prefs = requireContext().getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
        etOpenRouterKey?.setText(prefs.getString("openrouter_key", ""))
        etTavilyKey?.setText(prefs.getString("tavily_key", ""))

        // 新闻源单选默认选中第一个（人民日报）
        rgNewsSources?.check(R.id.rbNewsPeople)

        btnOpenRouterSite?.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/keys")))
        }
        btnTavilySite?.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.tavily.com/")))
        }

        btnSaveCloud?.setOnClickListener {
            saveCloudSettings()
            (activity as? OnboardingActivity)?.goNext()
        }

        btnSkipCloud?.setOnClickListener {
            // 跳过云端配置，直接进入下一步
            (activity as? OnboardingActivity)?.goNext()
        }

        btnPrev?.setOnClickListener {
            (activity as? OnboardingActivity)?.goPrevious()
        }
    }

    private fun saveCloudSettings() {
        val openRouterKey = etOpenRouterKey?.text?.toString()?.trim() ?: ""
        val tavilyKey = etTavilyKey?.text?.toString()?.trim() ?: ""
        val newsSourceId = rgNewsSources?.checkedRadioButtonId ?: R.id.rbNewsPeople

        val prefs = requireContext().getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("openrouter_key", openRouterKey)
            .putString("tavily_key", tavilyKey)
            .putInt("news_source_id", newsSourceId)
            .apply()

        // 保存到 onboarding ViewModel，供完成页判断是否弹"下载本地AI"提示
        viewModel.openRouterKey = openRouterKey
        viewModel.tavilyKey = tavilyKey
        viewModel.newsSourceId = newsSourceId
    }
}