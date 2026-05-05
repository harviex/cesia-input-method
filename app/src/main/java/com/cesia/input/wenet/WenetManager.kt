package com.cesia.input.wenet

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream

class WenetManager(private val context: Context) {
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    // WeNet模型路径
    private val modelPath = "${context.filesDir}/wenet_model.bin"
    private val unitPath = "${context.filesDir}/wenet_units.txt"
    
    init {
        copyAssetsToStorage()
        initWenet(modelPath, unitPath)
    }
    
    private fun copyAssetsToStorage() {
        // 从assets复制模型文件
        listOf("wenet_model.bin", "wenet_units.txt").forEach { filename ->
            val file = File(context.filesDir, filename)
            if (!file.exists()) {
                context.assets.open(filename).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
    
    fun startRecording() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate, channelConfig, audioFormat
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat,
            minBufferSize
        )
        
        audioRecord?.startRecording()
        isRecording = true
        
        // 在后台线程录音
        Thread {
            val buffer = ByteArray(minBufferSize)
            val outputFile = File(context.cacheDir, "recording.pcm")
            FileOutputStream(outputFile).use { fos ->
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        fos.write(buffer, 0, read)
                    }
                }
            }
            
            // 识别录音
            recognizeAudio(outputFile.absolutePath)
        }.start()
    }
    
    fun stopRecording(): String {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        return ""
    }
    
    private external fun initWenet(modelPath: String, unitPath: String)
    private external fun recognizeAudio(pcmPath: String): String
    
    companion object {
        init {
            System.loadLibrary("wenet-jni")
        }
    }
}
