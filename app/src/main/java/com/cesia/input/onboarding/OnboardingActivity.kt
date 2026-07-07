package com.cesia.input.onboarding

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.cesia.input.R

class OnboardingActivity : AppCompatActivity() {

    private var pager: ViewPager2? = null
    private var adapter: OnboardingAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.onboarding_pager)
        adapter = OnboardingAdapter(this)
        pager?.adapter = adapter
        pager?.isUserInputEnabled = false  // 禁用滑动，只通过按钮切换
    }

    fun goNext() {
        val current = pager?.currentItem ?: 0
        if (current < 3) {
            pager?.currentItem = current + 1
        }
    }

    fun goPrevious() {
        val current = pager?.currentItem ?: 0
        if (current > 0) {
            pager?.currentItem = current - 1
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, OnboardingActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }

        /** 判断是否首次启动（需显示引导） */
        fun isFirstLaunch(context: Context): Boolean {
            val prefs = context.getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
            return !prefs.getBoolean("onboarding_completed", false)
        }

        /** 标记引导已完成 */
        fun markCompleted(context: Context) {
            val prefs = context.getSharedPreferences("cesia_settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("onboarding_completed", true).apply()
        }
    }

    inner class OnboardingAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> WelcomeFragment()
            1 -> DownloadFragment()
            2 -> CloudSetupFragment()
            3 -> CompleteFragment()
            else -> WelcomeFragment()
        }
    }
}