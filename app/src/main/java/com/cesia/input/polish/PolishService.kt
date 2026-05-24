package com.cesia.input.polish

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 调用 AI 润色 API 的服务
 * 支持两种后端：
 * 1. OpenRouter (默认) — 免费模型，不限流
 * 2. 自定义 API — 兼容 {text, language} -> {polished_text} 格式
 */
class PolishService(
    private val scope: CoroutineScope,
    private var apiUrl: String = DEFAULT_OPENROUTER_URL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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

    /** 同步调用润色 API */
    suspend fun polishText(text: String): PolishResult = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length < 2) {
            return@withContext PolishResult.EmptyInput
        }

        try {
            if (isOpenRouterUrl(apiUrl)) {
                polishWithOpenRouter(text)
            } else {
                polishWithCustomApi(text)
            }
        } catch (e: IOException) {
            Log.e("PolishService", "网络错误", e)
            PolishResult.Error("网络连接失败: ${e.message ?: "未知"}", isNetworkError = true)
        } catch (e: Exception) {
            Log.e("PolishService", "处理错误", e)
            PolishResult.Error("处理失败: ${e.message ?: "未知"}")
        }
    }

    /** 判断是否为 OpenRouter URL */
    private fun isOpenRouterUrl(url: String): Boolean {
        return url.contains("openrouter.ai") || url.contains("api.cesia.cc")
    }

    /** 调用 OpenRouter API，支持429重试和备用模型 */
    private fun polishWithOpenRouter(text: String): PolishResult {
        val apiKey = getOpenRouterApiKey()
        if (apiKey.isNullOrEmpty()) {
            return PolishResult.Error("OpenRouter API Key 未配置")
        }

        val systemPrompt = "你是一个文本润色与输入排版高手。请将输入的口语文字处理为通顺的书面文字。\n\n严格要求：\n1. 只输出润色排版后的纯文本本身\n2. 严禁输出任何解释、说明、前缀或后缀\n3. 严禁删减核心信息\n4. 严禁随意扩写新内容\n5. 仅修正错别字、口语和语序，加入标点\n6. 如果内容包含多个观点或步骤，请通过换行分段或使用* 进行分点陈列"

        val models = listOf(_modelId, OPENROUTER_MODEL_FALLBACK)
        var lastError = ""

        for (model in models.distinct()) {
            val result = tryOpenRouterModel(text, systemPrompt, model, apiKey)
            if (result is PolishResult.Success) return result
            if (result is PolishResult.Error) {
                lastError = result.message
                // 429 限流时换下一个模型
                if (result.message.contains("429") || result.message.contains("rate limit")) {
                    Log.w("PolishService", "模型 $model 被限流，切换下一个")
                    continue
                }
                // 其他错误也尝试下一个模型
                continue
            }
        }
        return PolishResult.Error("所有模型均失败: $lastError")
    }

    private fun tryOpenRouterModel(text: String, systemPrompt: String, model: String, apiKey: String): PolishResult {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            })
        }

        // 根据文本长度动态设置 max_tokens，长文本需要更多 token
        val maxTokens = (text.length * 2).coerceIn(512, 2048)

        val json = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/harviex/cesia-input-method")
            .addHeader("X-Title", "Cesia Input Method")
            .build()

        Log.d("PolishService", "OpenRouter 请求 [$model]: ${text.take(50)}...")

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "未知错误"
                    Log.e("PolishService", "OpenRouter 错误 [$model]: ${response.code} - $errorBody")
                    return PolishResult.Error("API 错误(${response.code}): ${errorBody.take(200)}")
                }

                val respBody = response.body?.string()
                    ?: return PolishResult.Error("响应为空")

                Log.d("PolishService", "OpenRouter 响应 [$model]: ${respBody.take(200)}")

                val respJson = JSONObject(respBody)
                val choices = respJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val content = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    if (content.isNotEmpty()) {
                        return PolishResult.Success(text, content)
                    }
                }

                // 尝试其他格式
                val content = respJson.optString("content", "")
                if (content.isNotEmpty()) {
                    return PolishResult.Success(text, content)
                }

                PolishResult.Error("OpenRouter 返回格式异常: ${respBody.take(200)}")
            }
        } catch (e: Exception) {
            Log.e("PolishService", "OpenRouter 异常 [$model]", e)
            PolishResult.Error("网络错误: ${e.message ?: "未知"}", isNetworkError = true)
        }
    }

    /** 调用自定义 API（兼容旧格式） */
    private fun polishWithCustomApi(text: String): PolishResult {
        val json = JSONObject().apply {
            put("text", text)
            put("language", "zh")
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "CesiaIME/1.0")
            .build()

        Log.d("PolishService", "自定义 API 请求: $apiUrl, 文本长度: ${text.length}")

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "未知错误"
                Log.e("PolishService", "API 请求失败: ${response.code} - $errorBody")
                return PolishResult.Error(
                    "API 错误(${response.code}): ${errorBody.take(200)}",
                    isNetworkError = response.code in 400..599
                )
            }

            val respBody = response.body?.string()
                ?: return PolishResult.Error("响应为空")

            val result = parsePolishResponse(respBody)
            Log.d("PolishService", "润色结果: $result")
            return result
        }
    }

    /** 解析自定义 API 响应 */
    private fun parsePolishResponse(body: String): PolishResult {
        return try {
            val json = JSONObject(body)

            // 格式1: { "polished_text": "..." }
            if (json.has("polished_text")) {
                val polished = json.getString("polished_text")
                val original = json.optString("original", "")
                val confidence = json.optDouble("confidence", 1.0).toFloat()

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
            // 格式4: 直接是字符串
            else {
                return PolishResult.Error("API 返回格式不可解析")
            }
        } catch (e: Exception) {
            val trimmed = body.trim()
            if (isPlaceholder(trimmed)) {
                return PolishResult.Error("API 返回空结果 (placeholder)")
            }
            PolishResult.Success("", trimmed)
        }
    }

    private fun isPlaceholder(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "polished_text" ||
               t == "(polished_text)" ||
               t == "<polished_text>" ||
               t == "text" ||
               t == "..." ||
               t.isEmpty()
    }

    /** 获取 OpenRouter API Key */
    private fun getOpenRouterApiKey(): String? {
        return _apiKey
    }

    private var _apiKey: String? = null
    private var _modelId: String = OPENROUTER_MODEL

    fun updateApiKey(key: String) {
        _apiKey = key.trim()
        Log.d("PolishService", "OpenRouter API Key 已更新")
    }

    fun updateModelId(model: String) {
        _modelId = model.trim()
        Log.d("PolishService", "模型已更新为: $_modelId")
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

    /** 魔法模式：使用自定义 prompt 调用 API */
    fun polishWithPrompt(prompt: String): String? {
        return try {
            if (isOpenRouterUrl(apiUrl)) {
                polishWithPromptOpenRouter(prompt)
            } else {
                polishWithPromptCustom(prompt)
            }
        } catch (e: Exception) {
            Log.e("PolishService", "魔法模式异常", e)
            null
        }
    }

    private fun polishWithPromptOpenRouter(prompt: String): String? {
        val apiKey = getOpenRouterApiKey() ?: return null

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "你是一个文本编辑助手。根据用户指令修改原文。\n\n严格要求：\n1. 只输出修改后的文本本身\n2. 不要输出任何解释、说明、前缀或后缀\n3. 不要扩写新内容\n4. 不要删减核心信息\n5. 如果指令不明确，直接输出原文")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        // 魔法模式也支持多模型重试，prompt 包含原文需要更大 token
        val maxTokens = (prompt.length * 2).coerceIn(512, 4096)
        val models = listOf(_modelId, OPENROUTER_MODEL_FALLBACK)
        for (model in models.distinct()) {
            val json = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", maxTokens)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://github.com/harviex/cesia-input-method")
                .addHeader("X-Title", "Cesia Input Method")
                .build()

            try {
                val result = client.newCall(request).execute()
                if (result.isSuccessful) {
                    val respBody = result.body?.string() ?: continue
                    val respJson = JSONObject(respBody)
                    val choices = respJson.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val rawContent = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                        val cleaned = cleanAiResponse(rawContent)
                        if (cleaned.isNotEmpty()) {
                            return cleaned
                        }
                    }
                } else {
                    val errorBody = result.body?.string()?.take(100) ?: ""
                    Log.w("PolishService", "魔法模型 $model HTTP ${result.code}: $errorBody")
                    // 429 限流时换下一个模型
                    if (result.code == 429) continue
                    // 其他错误也尝试下一个模型
                    continue
                }
            } catch (e: Exception) {
                Log.w("PolishService", "魔法模型 $model 异常: ${e.message}")
            }
        }
        Log.e("PolishService", "魔法修改所有模型均失败")
        return null
    }

    /**
     * 清理 AI 响应，移除解释性文字
     */
    private fun cleanAiResponse(raw: String): String {
        var text = raw.trim()

        // 移除 markdown 代码块
        text = text.removePrefix("```").removeSuffix("```").trim()
        text = text.removePrefix("```json").removeSuffix("```").trim()

        // 移除常见的解释性前缀
        val explanationPrefixes = listOf(
            "修改后的文本：", "修改后：", "润色后：", "润色后的文本：",
            "结果：", "输出：", "回复：", "回答：",
            "Modified text:", "Result:", "Output:", "Here is",
            "以下是修改后的", "以下是润色后的", "修改结果："
        )
        for (prefix in explanationPrefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }

        // 移除引号包裹
        if (text.startsWith("\"") && text.endsWith("\"") && text.length > 1) {
            text = text.substring(1, text.length - 1).trim()
        }

        // 如果包含多行，只取第一段（避免解释性文字）
        val lines = text.lines()
        if (lines.size > 1) {
            // 检查是否有明显的解释性段落
            val meaningfulLines = lines.filter { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() &&
                !trimmed.startsWith("说明：") &&
                !trimmed.startsWith("解释：") &&
                !trimmed.startsWith("注意：") &&
                !trimmed.startsWith("Note:") &&
                !trimmed.startsWith("Explanation:")
            }
            if (meaningfulLines.isNotEmpty() && meaningfulLines.size < lines.size) {
                text = meaningfulLines.joinToString("\n")
            }
        }

        return text.trim()
    }

    private fun polishWithPromptCustom(prompt: String): String? {
        val json = JSONObject().apply {
            put("text", prompt)
            put("language", "zh")
        }
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val jsonResp = JSONObject(body)
            return jsonResp.optString("polished_text", "")
        }
    }

    companion object {
        const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val OPENROUTER_MODEL = "google/gemma-4-26b-a4b-it:free"
        const val OPENROUTER_MODEL_FALLBACK = "mistralai/mistral-7b-instruct:free"
        const val DEFAULT_CUSTOM_URL = "https://typeless-ai-service.vercel.app/api/polish"
    }
}
