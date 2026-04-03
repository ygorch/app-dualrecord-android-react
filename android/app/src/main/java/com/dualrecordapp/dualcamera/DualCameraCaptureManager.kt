package com.dualrecordapp.dualcamera

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile

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
    private var fd16_9: ParcelFileDescriptor? = null
    private var fd9_16: ParcelFileDescriptor? = null

    private var hasWrittenFrames16_9 = false
    private var hasWrittenFrames9_16 = false
    private var isRecording = false

    fun getAvailablePhysicalLenses(): List<PhysicalLens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableListOf<PhysicalLens>()
        try {
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)

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
                    break
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
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename16_9 = "REC_16_9_$timeStamp.mp4"
            val filename9_16 = "REC_9_16_$timeStamp.mp4"

            val prefs = context.getSharedPreferences("DualCameraPrefs", Context.MODE_PRIVATE)
            val uriStr = prefs.getString("output_directory_uri", null)

            if (uriStr != null && Build.VERSION_CODES.O <= Build.VERSION.SDK_INT) {
                // Opção A: SAF (Storage Access Framework) via DocumentFile e FileDescriptor
                val treeUri = Uri.parse(uriStr)
                val dir = DocumentFile.fromTreeUri(context, treeUri)
                if (dir != null && dir.exists()) {
                    val doc16_9 = dir.createFile("video/mp4", filename16_9)
                    val doc9_16 = dir.createFile("video/mp4", filename9_16)

                    doc16_9?.uri?.let { uri ->
                        fd16_9 = context.contentResolver.openFileDescriptor(uri, "rw")
                        fd16_9?.fileDescriptor?.let { fd ->
                            muxer16_9 = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        }
                    }

                    doc9_16?.uri?.let { uri ->
                        fd9_16 = context.contentResolver.openFileDescriptor(uri, "rw")
                        fd9_16?.fileDescriptor?.let { fd ->
                            muxer9_16 = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        }
                    }
                }
            } else {
                // Fallback: Diretório interno do app
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val file16_9 = File(dir, filename16_9)
                val file9_16 = File(dir, filename9_16)
                muxer16_9 = MediaMuxer(file16_9.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer9_16 = MediaMuxer(file9_16.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            // Setup audio format as requested: AAC, 48000 Hz, 128 kbps
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)

            Log.d("DualCameraManager", "Recording started successfully.")

        } catch (e: Exception) {
            isRecording = false
            Log.e("DualCameraManager", "Failed to start recording", e)
            throw e
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        // Safe Teardown Muxers and File Descriptors
        safeTeardownMuxer(muxer16_9, fd16_9, hasWrittenFrames16_9, "16:9")
        safeTeardownMuxer(muxer9_16, fd9_16, hasWrittenFrames9_16, "9:16")

        muxer16_9 = null
        muxer9_16 = null
        fd16_9 = null
        fd9_16 = null
        Log.d("DualCameraManager", "Recording stopped safely.")
    }

    private fun safeTeardownMuxer(muxer: MediaMuxer?, fd: ParcelFileDescriptor?, hasWrittenFrames: Boolean, label: String) {
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

        fd?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e("DualCameraManager", "Exception closing ParcelFileDescriptor for $label", e)
            }
        }
    }
}
