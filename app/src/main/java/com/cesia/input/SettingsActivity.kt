package com.cesia.input

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.Button
import android.widget.Toast
import com.cesia.input.api.ApiClient

class SettingsActivity: Activity() {
    
    private lateinit var apiUrlEdit: EditText
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var apiClient: ApiClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        
        apiClient = ApiClient(this)
        
        apiUrlEdit = findViewById(R.id.et_api_url)
        saveButton = findViewById(R.id.btn_save)
        resetButton = findViewById(R.id.btn_reset)
        
        // 显示当前API地址
        apiUrlEdit.setText(apiClient.getApiUrl())
        
        saveButton.setOnClickListener {
            val url = apiUrlEdit.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入API地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            apiClient.setApiUrl(url)
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
            finish()
        }
        
        resetButton.setOnClickListener {
            val defaultUrl = "https://typeless-ai-service.vercel.app/api/polish"
            apiUrlEdit.setText(defaultUrl)
            apiClient.setApiUrl(defaultUrl)
            Toast.makeText(this, "已重置为默认地址", Toast.LENGTH_SHORT).show()
        }
    }
}
