package com.cesia.input.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper 音频录制器
 * 录制 16kHz 单声道 PCM 音频，输出 float[] 供 whisper.cpp 使用
 */
class VoiceRecorder {

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 16000       // Whisper 要求 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_FACTOR = 4  // 增加到 4x，防止缓冲区溢出
        private const val READ_CHUNK_SIZE = 1024  // 统一读取块大小
    }

    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int = 0
    private var consecutiveReadErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 20

    val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun init(): Boolean {
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size")
            return false
        }

        bufferSize *= BUFFER_FACTOR

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                return false
            }
            consecutiveReadErrors = 0
            Log.i(TAG, "AudioRecord initialized (buffer=$bufferSize, minBuffer=${AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)})")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Mic permission denied", e)
            false
        }
    }

    fun start() {
        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord?.startRecording()
            Log.i(TAG, "Recording started, state=${audioRecord?.recordingState}")
        } else {
            Log.e(TAG, "start: AudioRecord not initialized, state=${audioRecord?.state}")
        }
    }

    /**
     * 录制指定时长的音频，返回 float[-1.0, 1.0] 数组
     * @param durationMs 录制时长(毫秒)
     * @return float 数组或 null（如果录制失败）
     */
    fun record(durationMs: Int): FloatArray? {
        if (!init()) {
            Log.e(TAG, "record: init() failed")
            return null
        }

        start()

        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val audioData = FloatArray(numSamples)
        val buffer = ShortArray(1024)
        var offset = 0

        val startTime = System.currentTimeMillis()
        var readErrors = 0
        var totalRead = 0

        while (offset < numSamples && (System.currentTimeMillis() - startTime) < durationMs + 500) {
            val toRead = minOf(buffer.size, numSamples - offset)
            val read = audioRecord?.read(buffer, 0, toRead)
            if (read == null) {
                Log.e(TAG, "record: audioRecord.read() returned null")
                break
            }
            if (read < 0) {
                readErrors++
                Log.w(TAG, "record: AudioRecord.read() returned $read (error #$readErrors)")
                if (readErrors > 10) {
                    Log.e(TAG, "record: too many read errors, aborting")
                    break
                }
                continue
            }
            if (read == 0) {
                // No data available yet, sleep briefly
                Thread.sleep(10)
                continue
            }

            for (i in 0 until read) {
                audioData[offset + i] = buffer[i] / 32768.0f
            }
            offset += read
            totalRead += read
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "record: finished, offset=$offset / $numSamples, totalRead=$totalRead, elapsed=${elapsed}ms, errors=$readErrors")

        stop()
        release()

        return if (offset > 0) {
            if (offset < audioData.size) audioData.copyOf(offset) else audioData
        } else null
    }

    /**
     * 持续录制模式：返回分段读取的 float 数组
     * 用于从 AudioRecord 实时读取 whisper 所需的 chunk
     */
     fun readChunk(chunkSize: Int): FloatArray? {
         if (audioRecord == null || !isRecording) {
             Log.w(TAG, "readChunk: AudioRecord not ready")
             return null
         }
        
         val actualChunkSize = minOf(chunkSize, READ_CHUNK_SIZE)
         val buffer = ShortArray(actualChunkSize)
         val read = try {
             audioRecord?.read(buffer, 0, actualChunkSize)
         } catch (e: Exception) {
             consecutiveReadErrors++
             Log.e(TAG, "readChunk: AudioRecord.read() threw exception (error #$consecutiveReadErrors)", e)
             if (consecutiveReadErrors >= MAX_CONSECUTIVE_ERRORS) {
                 Log.e(TAG, "readChunk: too many consecutive errors, forcing stop")
                 stop()
             }
             return null
         }
        
         if (read == null) {
             consecutiveReadErrors++
             Log.w(TAG, "readChunk: AudioRecord.read() returned null (error #$consecutiveReadErrors)")
             if (consecutiveReadErrors >= MAX_CONSECUTIVE_ERRORS) {
                 stop()
             }
             return null
         }
        
         if (read < 0) {
             consecutiveReadErrors++
             Log.w(TAG, "readChunk: AudioRecord.read() returned $read (error #$consecutiveReadErrors)")
             if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                 Log.e(TAG, "readChunk: AudioRecord invalid operation, stopping")
                 stop()
             }
             if (consecutiveReadErrors >= MAX_CONSECUTIVE_ERRORS) {
                 stop()
             }
             return null
         }
        
         if (read == 0) {
             // No data available yet - this is normal, not an error
             return FloatArray(0)
         }
        
         // 成功读取，重置错误计数
         consecutiveReadErrors = 0
        
         return FloatArray(read) { buffer[it] / 32768.0f }
     }

     fun stop() {
        try {
            if (isRecording) {
                audioRecord?.stop()
                Log.i(TAG, "Recording stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    fun release() {
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    /**
     * 获取最大振幅（用于 VU 表/语音活动检测）
     */
    fun readAmplitude(): Float {
        val buffer = ShortArray(512)
        val read = audioRecord?.read(buffer, 0, buffer.size) ?: return 0f
        if (read <= 0) return 0f

        var max = 0
        for (i in 0 until read) {
            val abs = kotlin.math.abs(buffer[i].toInt())
            if (abs > max) max = abs
        }
        return max / 32768.0f
    }
}
