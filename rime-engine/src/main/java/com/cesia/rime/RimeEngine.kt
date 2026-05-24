package com.cesia.rime

import android.util.Log
import com.cesia.rime.jni.RimeJni

/**
 * Rime 引擎实现
 * 通过 JNI 调用 librime 提供输入能力
 * 如果 librime 不可用，返回失败让上层回退到 Cesia 引擎
 */
class RimeEngine : InputEngine {

    companion object {
        private const val TAG = "RimeEngine"
        private const val VERSION_NAME = "1.1.1"
        private var isLibraryAvailable = false

        init {
            try {
                System.loadLibrary("rime_jni")
                isLibraryAvailable = true
                Log.i(TAG, "librime_jni loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isLibraryAvailable = false
                Log.w(TAG, "librime_jni not available, will fall back to Cesia engine", e)
            }
        }

        fun isAvailable(): Boolean = isLibraryAvailable
    }

    private var isInitialized = false

    override fun initialize(config: EngineConfig): Boolean {
        if (!isLibraryAvailable) {
            Log.w(TAG, "librime not available, skip initialization")
            return false
        }
        try {
            RimeJni.startup(
                config.sharedDataDir,
                config.userDataDir,
                VERSION_NAME,
                true
            )
            isInitialized = true
            Log.i(TAG, "RimeEngine initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RimeEngine", e)
            return false
        }
    }

    override fun createSession(): SessionHandle {
        return SessionHandle(1L)
    }

    override fun processKey(session: SessionHandle, keyCode: Int, mask: Int): ProcessResult {
        if (!isInitialized || !isLibraryAvailable) return ProcessResult.Rejected
        return try {
            val accepted = RimeJni.processKey(keyCode, mask)
            if (accepted) ProcessResult.Accepted else ProcessResult.Rejected
        } catch (e: Exception) {
            Log.e(TAG, "processKey failed", e)
            ProcessResult.Rejected
        }
    }

    override fun getCandidates(session: SessionHandle, limit: Int): List<Candidate> {
        if (!isInitialized || !isLibraryAvailable) return emptyList()
        return try {
            val candidates = RimeJni.getCandidates(0, limit)
            candidates.mapIndexed { index, text ->
                Candidate(text = text, comment = null, label = "${index + 1}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCandidates failed", e)
            emptyList()
        }
    }

    override fun commit(session: SessionHandle): String? {
        if (!isInitialized || !isLibraryAvailable) return null
        return try {
            RimeJni.getCommitText()
        } catch (e: Exception) {
            Log.e(TAG, "commit failed", e)
            null
        }
    }

    override fun clear(session: SessionHandle) {
        if (!isInitialized || !isLibraryAvailable) return
        try {
            RimeJni.clearComposition()
        } catch (e: Exception) {
            Log.e(TAG, "clear failed", e)
        }
    }

    override fun selectCandidate(session: SessionHandle, index: Int): Boolean {
        if (!isInitialized || !isLibraryAvailable) return false
        return try {
            RimeJni.selectCandidate(index, false)
        } catch (e: Exception) {
            Log.e(TAG, "selectCandidate failed", e)
            false
        }
    }

    override fun switchSchema(session: SessionHandle, schemaId: String): Boolean {
        if (!isInitialized || !isLibraryAvailable) return false
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
        if (!isInitialized || !isLibraryAvailable) return
        try {
            RimeJni.exit()
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
    }
}