package com.cesia.rime

import android.util.Log
import com.cesia.rime.jni.RimeJni

/**
 * Rime 引擎实现
 * 通过 JNI 调用 librime 提供输入能力
 */
class RimeEngine : InputEngine {

    companion object {
        private const val TAG = "RimeEngine"
        private const val VERSION_NAME = "1.1.1"
    }

    private var isInitialized = false
    private var isDeployed = false

    override fun initialize(config: EngineConfig): Boolean {
        try {
            // 1. 启动 librime
            RimeJni.startup(
                config.sharedDataDir,
                config.userDataDir,
                VERSION_NAME,
                !isDeployed  // 首次部署需要完整检查
            )

            // 2. 部署默认方案
            deployDefaultSchemas()

            isInitialized = true
            Log.i(TAG, "RimeEngine initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RimeEngine", e)
            return false
        }
    }

    private fun deployDefaultSchemas() {
        try {
            // 部署 Luna 拼音方案
            val schemaFile = "${RimeDefaults.SHARED_DATA_DIR}/luna_pinyin.schema.yaml"
            RimeJni.deploySchema(schemaFile)
            RimeJni.deployConfigFile("default", "default")
            isDeployed = true
            Log.i(TAG, "Default schemas deployed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy schemas", e)
        }
    }

    override fun createSession(): SessionHandle {
        // librime 使用单 session 模型
        return SessionHandle(1L)
    }

    override fun processKey(session: SessionHandle, keyCode: Int, mask: Int): ProcessResult {
        if (!isInitialized) return ProcessResult.Rejected

        return try {
            val accepted = RimeJni.processKey(keyCode, mask)
            if (!accepted) {
                ProcessResult.Rejected
            } else {
                ProcessResult.Accepted
            }
        } catch (e: Exception) {
            Log.e(TAG, "processKey failed", e)
            ProcessResult.Rejected
        }
    }

    override fun getCandidates(session: SessionHandle, limit: Int): List<Candidate> {
        if (!isInitialized) return emptyList()

        return try {
            val candidates = RimeJni.getCandidates(0, limit)
            candidates.mapIndexed { index, text ->
                Candidate(
                    text = text,
                    comment = null,
                    label = "${index + 1}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCandidates failed", e)
            emptyList()
        }
    }

    override fun commit(session: SessionHandle): String? {
        if (!isInitialized) return null

        return try {
            val text = RimeJni.getCommitText()
            if (text.isNullOrEmpty()) null else text
        } catch (e: Exception) {
            Log.e(TAG, "commit failed", e)
            null
        }
    }

    override fun clear(session: SessionHandle) {
        if (!isInitialized) return

        try {
            RimeJni.clearComposition()
        } catch (e: Exception) {
            Log.e(TAG, "clear failed", e)
        }
    }

    override fun selectCandidate(session: SessionHandle, index: Int): Boolean {
        if (!isInitialized) return false

        return try {
            RimeJni.selectCandidate(index, false)
        } catch (e: Exception) {
            Log.e(TAG, "selectCandidate failed", e)
            false
        }
    }

    override fun switchSchema(session: SessionHandle, schemaId: String): Boolean {
        if (!isInitialized) return false

        return try {
            RimeJni.selectSchema(schemaId)
        } catch (e: Exception) {
            Log.e(TAG, "switchSchema failed", e)
            false
        }
    }

    override fun destroySession(session: SessionHandle) {
        // librime 单 session，不做处理
    }

    override fun release() {
        try {
            RimeJni.exit()
            isInitialized = false
            Log.i(TAG, "RimeEngine released")
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
    }
}