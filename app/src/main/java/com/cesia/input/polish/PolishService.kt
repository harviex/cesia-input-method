package com.cesia.input.polish

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 调用 Typeless AI 润色 API 的服务
 * 支持 WeNet VAD 模式下分片上传 + 整段润色两种模式
 */
class PolishService(
    private val scope: CoroutineScope,
    private var apiUrl: String = "https://typeless-ai-service.vercel.app/api/polish"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class PolishResult {
        data class Success(
            val originalText: String,
            val polishedText: String,
            val confidence: Float = 1.0f
        ) : PolishResult()
        data class Error(val message: String, val isNetworkError: Boolean = false) : PolishResult()
        object EmptyInput : PolishResult()
    }

    private val _results = MutableSharedFlow<PolishResult>(replay = 0)
    val results = _results.asSharedFlow()

    /**
     * 同步调用润色 API
     * @param text 识别出的原始文本
     * @return 润色后的结果
     */
    suspend fun polishText(text: String): PolishResult = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length < 2) {
            return@withContext PolishResult.EmptyInput
        }

        try {
            val json = JSONObject().apply {
                put("text", text)
                put("language", "zh")
            }

            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            Log.d("PolishService", "请求 API: $apiUrl, 文本长度: ${text.length}")

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "未知错误"
                    Log.e("PolishService", "API 请求失败: ${response.code} - $errorBody")
                    return@withContext PolishResult.Error(
                        "API 错误(${response.code}): ${errorBody.take(200)}",
                        isNetworkError = response.code in 400..599
                    )
                }

                val body = response.body?.string() ?: return@withContext PolishResult.Error("响应为空")

                // 解析响应
                val result = parsePolishResponse(body)
                Log.d("PolishService", "润色结果: $result")
                result
            }
        } catch (e: IOException) {
            Log.e("PolishService", "网络错误", e)
            PolishResult.Error("网络连接失败: ${e.message ?: "未知"}", isNetworkError = true)
        } catch (e: Exception) {
            Log.e("PolishService", "处理错误", e)
            PolishResult.Error("处理失败: ${e.message ?: "未知"}")
        }
    }

    /**
     * 使用 WeNet 分片识别 + 流式上传
     * 适用于需要实时反馈的场景
     */
    suspend fun polishChunks(chunks: List<String>): PolishResult = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext PolishResult.EmptyInput
        val fullText = chunks.joinToString("")
        return@withContext polishText(fullText)
    }

    /**
     * 解析润色 API 响应
     * 支持多种响应格式
     */
    private fun parsePolishResponse(body: String): PolishResult {
        return try {
            val json = JSONObject(body)

            // 格式1: { "polished_text": "..." }
            if (json.has("polished_text")) {
                val polished = json.getString("polished_text")
                val original = json.optString("original", "")
                val confidence = json.optDouble("confidence", 1.0).toFloat()
                
                // 如果 API 返回 placeholder 标记，视为润色失败
                if (isPlaceholder(polished)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                
                PolishResult.Success(original, polished, confidence)
            }
            // 格式2: { "result": "..." }
            else if (json.has("result")) {
                val result = json.getString("result")
                if (isPlaceholder(result)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                PolishResult.Success(body, result)
            }
            // 格式3: { "data": { "text": "..." } }
            else if (json.has("data")) {
                val data = json.getJSONObject("data")
                val text = data.optString("text", data.optString("polished", ""))
                if (isPlaceholder(text)) {
                    return PolishResult.Error("API 返回空结果 (placeholder)")
                }
                PolishResult.Success(body, text)
            }
            // 格式4: 直接是字符串 — 如果 JSON 没有有效字段，返回错误
            else {
                return PolishResult.Error("API 返回格式不可解析")
            }
        } catch (e: Exception) {
            // 如果不是 JSON，直接作为润色后的文本返回
            val trimmed = body.trim()
            if (isPlaceholder(trimmed)) {
                return PolishResult.Error("API 返回空结果 (placeholder)")
            }
            PolishResult.Success("", trimmed)
        }
    }

    /**
     * 检测 API 返回是否为占位符文本
     */
    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "polished_text" ||
               t == "(polished_text)" ||
               t == "<polished_text>" ||
               t == "text" ||
               t == "..." ||
               t.isEmpty()
    }

    fun updateApiUrl(newUrl: String) {
        apiUrl = newUrl.trim()
        Log.d("PolishService", "API URL 更新为: $apiUrl")
    }

    fun getApiUrl(): String = apiUrl

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * 魔法模式：使用自定义 prompt 调用 API
     * 用于语音指令修改文字
     */
    fun polishWithPrompt(prompt: String): String? {
        return try {
            val json = JSONObject().apply {
                put("text", prompt)
                put("language", "zh")
            }
            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()
            Log.d("PolishService", "魔法模式请求: ${prompt.take(100)}")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("PolishService", "魔法模式 API 错误: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val jsonResp = JSONObject(body)
                val result = jsonResp.optString("polished_text", "")
                Log.d("PolishService", "魔法模式结果: ${result.take(100)}")
                result
            }
        } catch (e: Exception) {
            Log.e("PolishService", "魔法模式异常", e)
            null
        }
    }
}