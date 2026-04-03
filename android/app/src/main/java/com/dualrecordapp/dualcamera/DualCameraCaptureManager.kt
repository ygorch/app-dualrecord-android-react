package com.dualrecordapp.dualcamera

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhysicalLens(val id: String, val focalLength: Float, val label: String)

@RequiresApi(Build.VERSION_CODES.P)
class DualCameraCaptureManager(private val context: Context) {

    private var muxer16_9: MediaMuxer? = null
    private var muxer9_16: MediaMuxer? = null

    private var hasWrittenFrames16_9 = false
    private var hasWrittenFrames9_16 = false
    private var isRecording = false

    fun getAvailablePhysicalLenses(): List<PhysicalLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableListOf<PhysicalLens>()
        try {
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)

                // We specifically want the back-facing camera
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val isLogical = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ) == true

                if (isLogical) {
                    val physicalCameraIds = chars.physicalCameraIds
                    for (physId in physicalCameraIds) {
                        val physChars = manager.getCameraCharacteristics(physId)
                        val focalLengths = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focalLength = focalLengths?.firstOrNull() ?: 0f

                        val label = when {
                            focalLength < 2.0f -> "0.6x (Ultrawide)"
                            focalLength in 2.0f..3.0f -> "1x (Principal)"
                            focalLength > 3.0f -> "3x (Telephoto)"
                            else -> "Unknown"
                        }
                        lenses.add(PhysicalLens(physId, focalLength, label))
                    }
                    break // Only grab physical lenses of the first back logical multi-camera
                }
            }
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error getting camera characteristics", e)
        }

        return lenses
    }

    fun startRecording() {
        if (isRecording) return
        isRecording = true
        hasWrittenFrames16_9 = false
        hasWrittenFrames9_16 = false

        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            val file16_9 = File(dir, "REC_16_9_$timeStamp.mp4")
            val file9_16 = File(dir, "REC_9_16_$timeStamp.mp4")

            muxer16_9 = MediaMuxer(file16_9.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer9_16 = MediaMuxer(file9_16.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Setup audio format as requested: AAC, 48000 Hz, 128 kbps
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)

            // Note: In real app, we would add video and audio tracks from MediaCodec and call MediaMuxer.start()
            // For MVP skeleton, we mock successful initialization.

            Log.d("DualCameraManager", "Recording started. Files: \n${file16_9.absolutePath}\n${file9_16.absolutePath}")

        } catch (e: Exception) {
            isRecording = false
            Log.e("DualCameraManager", "Failed to start recording", e)
            throw e
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        // Safe Teardown
        safeTeardownMuxer(muxer16_9, hasWrittenFrames16_9, "16:9")
        safeTeardownMuxer(muxer9_16, hasWrittenFrames9_16, "9:16")

        muxer16_9 = null
        muxer9_16 = null
        Log.d("DualCameraManager", "Recording stopped safely.")
    }

    private fun safeTeardownMuxer(muxer: MediaMuxer?, hasWrittenFrames: Boolean, label: String) {
        muxer?.let {
            try {
                if (hasWrittenFrames) {
                    it.stop()
                    Log.d("DualCameraManager", "Muxer $label stopped successfully.")
                } else {
                    Log.w("DualCameraManager", "Muxer $label did not receive any frames. Not calling stop() to prevent IllegalStateException.")
                }
            } catch (e: IllegalStateException) {
                Log.e("DualCameraManager", "IllegalStateException while stopping Muxer $label", e)
            } catch (e: Exception) {
                 Log.e("DualCameraManager", "Exception while stopping Muxer $label", e)
            } finally {
                try {
                    it.release()
                    Log.d("DualCameraManager", "Muxer $label released.")
                } catch (e: Exception) {
                    Log.e("DualCameraManager", "Exception while releasing Muxer $label", e)
                }
            }
        }
    }
}
