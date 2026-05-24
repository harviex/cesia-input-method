package com.cesia.rime.jni

/**
 * librime JNI 绑定层
 * 参考了 librime 的 C API 设计，代码完全独立实现
 */
internal object RimeJni {

    init {
        System.loadLibrary("rime_jni")
    }

    // ========== 生命周期 ==========

    external fun startup(
        sharedDataDir: String,
        userDataDir: String,
        versionName: String,
        fullCheck: Boolean
    )

    external fun exit()

    // ========== 部署 ==========

    external fun deploySchema(schemaFile: String): Boolean

    external fun deployConfigFile(fileName: String, versionKey: String): Boolean

    external fun syncUserData(): Boolean

    // ========== 输入 ==========

    external fun processKey(keyCode: Int, mask: Int): Boolean

    external fun commitComposition(): Boolean

    external fun clearComposition()

    // ========== 输出 ==========

    external fun getCommitText(): String?

    external fun getContextText(): String?

    external fun getCandidates(startIndex: Int, limit: Int): Array<String>

    external fun getStatusSchemaId(): String?

    // ========== 运行时选项 ==========

    external fun setOption(option: String, value: Boolean)

    external fun getOption(option: String): Boolean

    // ========== 方案管理 ==========

    external fun getSchemaList(): Array<String>

    external fun getCurrentSchema(): String?

    external fun selectSchema(schemaId: String): Boolean

    // ========== 候选词操作 ==========

    external fun selectCandidate(index: Int, global: Boolean): Boolean

    external fun deleteCandidate(index: Int, global: Boolean): Boolean

    external fun changePage(backward: Boolean): Boolean

    // ========== 辅助 ==========

    external fun getRawInput(): String?

    external fun getCaretPos(): Int

    external fun setCaretPosition(caretPos: Int)

    external fun simulateKeySequence(keySequence: String): Boolean
}