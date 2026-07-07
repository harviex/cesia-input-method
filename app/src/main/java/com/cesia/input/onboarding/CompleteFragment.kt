package com.cesia.input.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import com.cesia.input.R

class CompleteFragment : Fragment(R.layout.fragment_complete) {

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> finishOnboarding() }

    // Views
    private var btnFinish: android.widget.Button? = null
    private var btnPrev: android.widget.Button? = null
    private var btnOpenImeSettings: android.widget.Button? = null
    private var btnOpenKeyboardPicker: android.widget.Button? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)

        btnFinish?.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                finishOnboarding()
            }
        }

        btnOpenImeSettings?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnOpenKeyboardPicker?.setOnClickListener {
            // 直接弹出输入法选择器
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        }

        btnPrev?.setOnClickListener {
            (activity as? OnboardingActivity)?.goPrevious()
        }
    }

    private fun bindViews(view: View) {
        btnFinish = view.findViewById(R.id.btnFinish)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnOpenImeSettings = view.findViewById(R.id.btnOpenImeSettings)
        btnOpenKeyboardPicker = view.findViewById(R.id.btnOpenKeyboardPicker)
    }

    private fun finishOnboarding() {
        requireActivity().finish()
    }
}