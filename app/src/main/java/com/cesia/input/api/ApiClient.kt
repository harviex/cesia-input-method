package com.cesia.input.api

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException

class ApiClient(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("cesia", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val client = OkHttpClient()
    
    fun getApiUrl(): String {
        return prefs.getString("api_url", "https://typeless-ai-service.vercel.app/api/polish") ?: ""
    }
    
    fun setApiUrl(url: String) {
        prefs.edit().putString("api_url", url).apply()
    }
    
    fun polish(text: String, callback: (String) -> Unit) {
        val url = getApiUrl()
        
        val json = gson.toJson(mapOf("text" to text))
        val body = RequestBody.create("application/json".toMediaType(), json)
        
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(text) // 失败时返回原文
            }
            
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val result = gson.fromJson(responseBody, PolishResponse::class.java)
                        callback(result.polished_text ?: text)
                    } catch (e: Exception) {
                        callback(text)
                    }
                } else {
                    callback(text)
                }
            }
        })
    }
    
    data class PolishResponse(val polished_text: String?)
}
