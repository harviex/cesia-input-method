package com.osfans.trime.core

/**
 * Trime Rime JNI 桥接类
 * 包名必须保持为 com.osfans.trime.core，以匹配 librime_jni.so 中的 JNI 符号
 * 例：Java_com_osfans_trime_core_Rime_startupRime
 */
class Rime {
    companion object {
        @JvmStatic external fun startupRime(sharedDir: String, userDir: String, versionName: String, fullCheck: Boolean)
        @JvmStatic external fun exitRime()
        @JvmStatic external fun deployRimeSchemaFile(schemaFile: String): Boolean
        @JvmStatic external fun deployRimeConfigFile(fileName: String, versionKey: String): Boolean
        @JvmStatic external fun syncRimeUserData(): Boolean
        @JvmStatic external fun processRimeKey(keycode: Int, mask: Int): Boolean
        @JvmStatic external fun commitRimeComposition(): Boolean
        @JvmStatic external fun clearRimeComposition()
        @JvmStatic external fun getRimeCommit(): CommitProto
        @JvmStatic external fun getRimeContext(): ContextProto
        @JvmStatic external fun getRimeStatus(): StatusProto
        @JvmStatic external fun setRimeOption(option: String, value: Boolean)
        @JvmStatic external fun getRimeOption(option: String): Boolean
        @JvmStatic external fun getRimeSchemaList(): Array<SchemaItem>
        @JvmStatic external fun getCurrentRimeSchema(): String
        @JvmStatic external fun selectRimeSchema(schemaId: String): Boolean
        @JvmStatic external fun simulateRimeKeySequence(keySequence: String): Boolean
        @JvmStatic external fun getRimeRawInput(): String
        @JvmStatic external fun getRimeCaretPos(): Int
        @JvmStatic external fun setRimeCaretPos(caretPos: Int)
        @JvmStatic external fun selectRimeCandidate(index: Int, global: Boolean): Boolean
        @JvmStatic external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean
        @JvmStatic external fun changeRimeCandidatePage(backward: Boolean): Boolean
        @JvmStatic external fun getAvailableRimeSchemaList(): Array<SchemaItem>
        @JvmStatic external fun getSelectedRimeSchemaList(): Array<SchemaItem>
        @JvmStatic external fun selectRimeSchemas(schemaIds: Array<String>): Boolean
        @JvmStatic external fun getRimeCandidates(startIndex: Int, limit: Int): Array<CandidateItem>
        @JvmStatic external fun getRimeBulkCandidates(): Array<Any>

        /**
         * Native 回调入口 — librime_jni 通过此方法通知 Java 层 Rime 事件
         * @param type 事件类型
         * @param params 事件参数
         */
        @JvmStatic
        fun handleRimeMessage(type: Int, params: Array<Any>) {
            // 事件回调（暂不处理，避免 native 崩溃）
        }
    }
}
