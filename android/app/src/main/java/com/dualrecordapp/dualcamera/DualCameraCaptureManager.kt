package com.dualrecordapp.dualcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

data class PhysicalLens(val id: String, val focalLength: Float, val label: String)

@RequiresApi(Build.VERSION_CODES.P)
class DualCameraCaptureManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: DualCameraCaptureManager? = null

        fun getInstance(context: Context): DualCameraCaptureManager {
            return instance ?: synchronized(this) {
                instance ?: DualCameraCaptureManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var logicalCameraId: String? = null

    private var surface16_9: Surface? = null
    private var surface9_16: Surface? = null
    private var lens16_9Id: String? = null
    private var lens9_16Id: String? = null

    private var muxer16_9: MediaMuxer? = null
    private var muxer9_16: MediaMuxer? = null
    private var fd16_9: ParcelFileDescriptor? = null
    private var fd9_16: ParcelFileDescriptor? = null

    private var hasWrittenFrames16_9 = false
    private var hasWrittenFrames9_16 = false
    private var isRecording = false

    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    fun getAvailablePhysicalLenses(): List<PhysicalLens> {
        val lenses = mutableListOf<PhysicalLens>()
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)

                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val isLogical = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ) == true

                if (isLogical) {
                    logicalCameraId = cameraId
                    val physicalCameraIds = chars.physicalCameraIds
                    for (physId in physicalCameraIds) {
                        val physChars = cameraManager.getCameraCharacteristics(physId)
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

    fun setPreviewSurface(surfaceTexture: SurfaceTexture, width: Int, height: Int, isSecondary: Boolean, activeLensId: String?) {
        surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTexture)

        if (isSecondary) {
            surface9_16 = surface
            lens9_16Id = activeLensId
        } else {
            surface16_9 = surface
            lens16_9Id = activeLensId
        }

        checkAndStartCamera()
    }

    fun removePreviewSurface(isSecondary: Boolean) {
        if (isSecondary) {
            surface9_16?.release()
            surface9_16 = null
        } else {
            surface16_9?.release()
            surface16_9 = null
        }
    }

    fun updateLensSelection(isSecondary: Boolean, activeLensId: String?) {
        if (isSecondary) {
            if (lens9_16Id == activeLensId) return
            lens9_16Id = activeLensId
        } else {
            if (lens16_9Id == activeLensId) return
            lens16_9Id = activeLensId
        }

        if (surface16_9 != null && surface9_16 != null) {
            startPreviewSession()
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkAndStartCamera() {
        if (surface16_9 == null || surface9_16 == null) return
        if (logicalCameraId == null) {
             getAvailablePhysicalLenses() // Ensure logicalCameraId is populated
        }
        if (logicalCameraId == null) return

        if (cameraDevice == null) {
            try {
                cameraManager.openCamera(logicalCameraId!!, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        startPreviewSession()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        Log.e("DualCameraManager", "Camera open error: \$error")
                    }
                }, backgroundHandler)
            } catch (e: Exception) {
                Log.e("DualCameraManager", "Failed to open camera", e)
            }
        } else {
            startPreviewSession()
        }
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val s16_9 = surface16_9 ?: return
        val s9_16 = surface9_16 ?: return

        try {
            captureSession?.close()

            val out16_9 = OutputConfiguration(s16_9)
            lens16_9Id?.let { out16_9.setPhysicalCameraId(it) }

            val out9_16 = OutputConfiguration(s9_16)
            lens9_16Id?.let { out9_16.setPhysicalCameraId(it) }

            val outputs = listOf(out16_9, out9_16)

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                object : Executor {
                    override fun execute(command: Runnable) {
                        backgroundHandler.post(command)
                    }
                },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(s16_9)
                            builder.addTarget(s9_16)
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        } catch (e: Exception) {
                            Log.e("DualCameraManager", "Failed to set repeating request", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("DualCameraManager", "Camera session configuration failed")
                    }
                }
            )

            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Failed to start preview session", e)
        }
    }

    fun startRecording() {
        if (isRecording) return
        isRecording = true
        hasWrittenFrames16_9 = false
        hasWrittenFrames9_16 = false

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename16_9 = "REC_16_9_\$timeStamp.mp4"
            val filename9_16 = "REC_9_16_\$timeStamp.mp4"

            val prefs = context.getSharedPreferences("DualCameraPrefs", Context.MODE_PRIVATE)
            val uriStr = prefs.getString("output_directory_uri", null)

            if (uriStr != null && Build.VERSION_CODES.O <= Build.VERSION.SDK_INT) {
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
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val file16_9 = File(dir, filename16_9)
                val file9_16 = File(dir, filename9_16)
                muxer16_9 = MediaMuxer(file16_9.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer9_16 = MediaMuxer(file9_16.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

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
                    Log.d("DualCameraManager", "Muxer \$label stopped successfully.")
                } else {
                    Log.w("DualCameraManager", "Muxer \$label did not receive any frames. Not calling stop() to prevent IllegalStateException.")
                }
            } catch (e: IllegalStateException) {
                Log.e("DualCameraManager", "IllegalStateException while stopping Muxer \$label", e)
            } catch (e: Exception) {
                 Log.e("DualCameraManager", "Exception while stopping Muxer \$label", e)
            } finally {
                try {
                    it.release()
                    Log.d("DualCameraManager", "Muxer \$label released.")
                } catch (e: Exception) {
                    Log.e("DualCameraManager", "Exception while releasing Muxer \$label", e)
                }
            }
        }

        fd?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e("DualCameraManager", "Exception closing ParcelFileDescriptor for \$label", e)
            }
        }
    }
}
