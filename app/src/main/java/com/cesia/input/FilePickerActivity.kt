package com.cesia.input

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 透明辅助 Activity，用于从 IME 弹出文件选择器。
 * 选中 txt 文件后读取内容（≤1MB），通过 SharedPreferences 传回 CesiaInputMethod。
 */
class FilePickerActivity : Activity() {

    companion object {
        const val RESULT_KEY_FILE_CONTENT = "local_lib_content"
        const val RESULT_KEY_FILE_NAME = "local_lib_name"
        private const val MAX_FILE_SIZE = 1024 * 1024 // 1MB
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()

                    if (bytes != null && bytes.size <= MAX_FILE_SIZE) {
                        val content = String(bytes, Charsets.UTF_8)
                        val fileName = getFileName(uri)

                        // 保存到 SharedPreferences
                        val prefs = getSharedPreferences("cesia_smart_writing", MODE_PRIVATE)
                        prefs.edit()
                            .putString(RESULT_KEY_FILE_CONTENT, content)
                            .putString(RESULT_KEY_FILE_NAME, fileName)
                            .apply()
                    }
                } catch (e: Exception) {
                    // 读取失败，不保存
                }
            }
        }
        finish()
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown.txt"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx) ?: name
                }
            }
        } catch (_: Exception) {}
        return name
    }
}
