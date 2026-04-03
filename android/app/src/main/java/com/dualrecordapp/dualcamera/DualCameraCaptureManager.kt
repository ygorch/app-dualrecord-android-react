package com.dualrecordapp.dualcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
import androidx.core.content.ContextCompat
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

    private var videoCodec16_9: MediaCodec? = null
    private var videoCodec9_16: MediaCodec? = null
    private var inputSurface16_9: Surface? = null
    private var inputSurface9_16: Surface? = null
    private var videoTrackIndex16_9 = -1
    private var videoTrackIndex9_16 = -1

    private var hasWrittenFrames16_9 = false
    private var hasWrittenFrames9_16 = false
    private var isRecording = false
    private var isMuxerStarted16_9 = false
    private var isMuxerStarted9_16 = false

    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val encodingThread = HandlerThread("VideoEncoding").apply { start() }
    private val encodingHandler = Handler(encodingThread.looper)

    fun getAvailablePhysicalLenses(): List<PhysicalLens> {
        val lenses = mutableListOf<PhysicalLens>()
        try {
            var foundLogical = false
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
                    foundLogical = true
                    break
                }
            }

            if (!foundLogical) {
                 for (cameraId in cameraManager.cameraIdList) {
                     val chars = cameraManager.getCameraCharacteristics(cameraId)
                     val facing = chars.get(CameraCharacteristics.LENS_FACING)
                     if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                         if (logicalCameraId == null) logicalCameraId = cameraId
                         val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                         val focalLength = focalLengths?.firstOrNull() ?: 0f
                         lenses.add(PhysicalLens(cameraId, focalLength, "1x (Principal)"))
                     }
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

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DualCameraManager", "Camera permission not granted")
            return
        }

        if (logicalCameraId == null) {
             getAvailablePhysicalLenses()
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
                        Log.e("DualCameraManager", "Camera open error: $error")
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
            if (lens16_9Id != null && lens16_9Id != logicalCameraId) {
                out16_9.setPhysicalCameraId(lens16_9Id!!)
            }

            val out9_16 = OutputConfiguration(s9_16)
            if (lens9_16Id != null && lens9_16Id != logicalCameraId) {
                out9_16.setPhysicalCameraId(lens9_16Id!!)
            }

            val outputs = mutableListOf(out16_9, out9_16)

            // If recording, add encoder surfaces to the capture session
            if (isRecording) {
                inputSurface16_9?.let {
                    val recOut16_9 = OutputConfiguration(it)
                    if (lens16_9Id != null && lens16_9Id != logicalCameraId) {
                        recOut16_9.setPhysicalCameraId(lens16_9Id!!)
                    }
                    outputs.add(recOut16_9)
                }
                inputSurface9_16?.let {
                    val recOut9_16 = OutputConfiguration(it)
                    if (lens9_16Id != null && lens9_16Id != logicalCameraId) {
                        recOut9_16.setPhysicalCameraId(lens9_16Id!!)
                    }
                    outputs.add(recOut9_16)
                }
            }

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
                            val template = if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                            val builder = device.createCaptureRequest(template)
                            builder.addTarget(s16_9)
                            builder.addTarget(s9_16)

                            if (isRecording) {
                                inputSurface16_9?.let { builder.addTarget(it) }
                                inputSurface9_16?.let { builder.addTarget(it) }
                            }

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

    private fun setupEncoder(width: Int, height: Int): Pair<MediaCodec, Surface> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10000000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()
        return Pair(encoder, inputSurface)
    }

    fun startRecording() {
        if (isRecording) return
        isRecording = true
        hasWrittenFrames16_9 = false
        hasWrittenFrames9_16 = false
        isMuxerStarted16_9 = false
        isMuxerStarted9_16 = false

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename16_9 = "REC_16_9_${timeStamp}.mp4"
            val filename9_16 = "REC_9_16_${timeStamp}.mp4"

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

            // Initialize Encoders (Mock resolutions 1280x720, 720x1280 for simplicity)
            val enc16_9 = setupEncoder(1280, 720)
            videoCodec16_9 = enc16_9.first
            inputSurface16_9 = enc16_9.second

            val enc9_16 = setupEncoder(720, 1280)
            videoCodec9_16 = enc9_16.first
            inputSurface9_16 = enc9_16.second

            startPreviewSession() // Restart session to include encoding surfaces

            encodingHandler.post { drainEncoder(videoCodec16_9!!, muxer16_9, "16:9", true) }
            encodingHandler.post { drainEncoder(videoCodec9_16!!, muxer9_16, "9:16", false) }

            Log.d("DualCameraManager", "Recording started successfully.")
        } catch (e: Exception) {
            isRecording = false
            Log.e("DualCameraManager", "Failed to start recording", e)
            throw e
        }
    }

    private fun drainEncoder(encoder: MediaCodec, muxer: MediaMuxer?, label: String, is16_9: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L

        while (isRecording) {
            try {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    muxer?.let {
                        if (is16_9) {
                            videoTrackIndex16_9 = it.addTrack(newFormat)
                            it.start()
                            isMuxerStarted16_9 = true
                        } else {
                            videoTrackIndex9_16 = it.addTrack(newFormat)
                            it.start()
                            isMuxerStarted9_16 = true
                        }
                    }
                } else if (encoderStatus >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                    if (encodedData != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)

                            val trackIndex = if (is16_9) videoTrackIndex16_9 else videoTrackIndex9_16
                            val isMuxerStarted = if (is16_9) isMuxerStarted16_9 else isMuxerStarted9_16

                            if (isMuxerStarted && trackIndex >= 0) {
                                muxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                                if (is16_9) hasWrittenFrames16_9 = true else hasWrittenFrames9_16 = true
                            }
                        }

                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DualCameraManager", "Error draining encoder $label", e)
                break
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        startPreviewSession() // Restart session to remove encoding surfaces

        try {
            videoCodec16_9?.signalEndOfInputStream()
            videoCodec9_16?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error signaling EOF", e)
        }

        // Wait a bit for encoders to finish draining
        Thread.sleep(500)

        videoCodec16_9?.stop()
        videoCodec16_9?.release()
        inputSurface16_9?.release()
        videoCodec16_9 = null
        inputSurface16_9 = null

        videoCodec9_16?.stop()
        videoCodec9_16?.release()
        inputSurface9_16?.release()
        videoCodec9_16 = null
        inputSurface9_16 = null

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
                    Log.d("DualCameraManager", "Muxer ${label} stopped successfully.")
                } else {
                    Log.w("DualCameraManager", "Muxer ${label} did not receive any frames. Not calling stop() to prevent IllegalStateException.")
                }
            } catch (e: IllegalStateException) {
                Log.e("DualCameraManager", "IllegalStateException while stopping Muxer ${label}", e)
            } catch (e: Exception) {
                 Log.e("DualCameraManager", "Exception while stopping Muxer ${label}", e)
            } finally {
                try {
                    it.release()
                    Log.d("DualCameraManager", "Muxer ${label} released.")
                } catch (e: Exception) {
                    Log.e("DualCameraManager", "Exception while releasing Muxer ${label}", e)
                }
            }
        }

        fd?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e("DualCameraManager", "Exception closing ParcelFileDescriptor for ${label}", e)
            }
        }
    }
}
