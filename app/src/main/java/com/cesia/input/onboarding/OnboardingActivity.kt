package com.cesia.input.onboarding

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.cesia.input.R

class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var viewModel: OnboardingViewModel
    private val prefs: SharedPreferences by lazy { getSharedPreferences("cesia_onboarding", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.onboarding_pager)
        viewModel = ViewModelProvider(this)[OnboardingViewModel::class.java]

        val adapter = OnboardingPagerAdapter(this)
        pager.adapter = adapter
        pager.isUserInputEnabled = false // 只能通过 "下一步" 按钮翻页

        // 监听最后一页完成
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == adapter.itemCount - 1) {
                    // 进入完成页时，标记 onboarding 完成
                    markCompleted()
                }
            }
        })
    }

    private fun markCompleted() {
        prefs.edit().putBoolean("first_run_complete", true).apply()
    }

    /** 供 Fragment 调用：跳转到下一页 */
    fun goNext() {
        if (pager.currentItem < pager.adapter?.itemCount!! - 1) {
            pager.currentItem++
        }
    }

    /** 供 Fragment 调用：跳转到上一页 */
    fun goPrevious() {
        if (pager.currentItem > 0) {
            pager.currentItem--
        }
    }

    /** 供 Fragment 调用：跳过云端设置直接去完成页 */
    fun skipToComplete() {
        pager.currentItem = pager.adapter?.itemCount!! - 1
    }

    inner class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> WelcomeFragment()
            1 -> DownloadFragment()
            2 -> CloudSetupFragment()
            3 -> CompleteFragment()
            else -> throw IllegalStateException()
        }
    }
}