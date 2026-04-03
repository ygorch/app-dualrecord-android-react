package com.dualrecordapp.dualcamera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap

@RequiresApi(Build.VERSION_CODES.P)
class DualCameraEngineModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val captureManager = DualCameraCaptureManager.getInstance(reactContext)
    private var directoryPromise: Promise? = null

    private val activityEventListener: ActivityEventListener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, intent: Intent?) {
            if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE) {
                if (resultCode == Activity.RESULT_OK) {
                    intent?.data?.let { uri ->
                        try {
                            val takeFlags: Int = intent.flags and
                                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            reactApplicationContext.contentResolver.takePersistableUriPermission(uri, takeFlags)

                            val prefs = reactApplicationContext.getSharedPreferences("DualCameraPrefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("output_directory_uri", uri.toString()).apply()

                            directoryPromise?.resolve(uri.toString())
                        } catch (e: Exception) {
                            directoryPromise?.reject("SAF_ERROR", "Failed to persist permission", e)
                        }
                    } ?: run {
                        directoryPromise?.resolve(null)
                    }
                } else {
                    directoryPromise?.resolve(null)
                }
                directoryPromise = null
            }
        }
    }

    init {
        reactContext.addActivityEventListener(activityEventListener)
    }

    override fun getName(): String {
        return "DualCameraEngine"
    }

    @ReactMethod
    fun getAvailablePhysicalLenses(promise: Promise) {
        try {
            val lenses = captureManager.getAvailablePhysicalLenses()
            val array = WritableNativeArray()
            for (lens in lenses) {
                val map = WritableNativeMap()
                map.putString("id", lens.id)
                map.putDouble("focalLength", lens.focalLength.toDouble())
                map.putString("label", lens.label)
                array.pushMap(map)
            }
            promise.resolve(array)
        } catch (e: Exception) {
            promise.reject("LENS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun startRecording(promise: Promise) {
        try {
            captureManager.startRecording()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("START_RECORD_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun stopRecording(promise: Promise) {
        try {
            captureManager.stopRecording()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("STOP_RECORD_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun selectOutputDirectory(promise: Promise) {
        val currentActivity = currentActivity
        if (currentActivity == null) {
            promise.reject("E_ACTIVITY_DOES_NOT_EXIST", "Activity doesn't exist")
            return
        }

        try {
            directoryPromise = promise
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            currentActivity.startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE)
        } catch (e: Exception) {
            directoryPromise = null
            promise.reject("E_FAILED_TO_SHOW_PICKER", e.message, e)
        }
    }

    @ReactMethod
    fun getSavedOutputDirectory(promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("DualCameraPrefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("output_directory_uri", null)

        if (uriStr != null) {
            val hasPermission = reactApplicationContext.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == uriStr && it.isWritePermission
            }
            if (!hasPermission) {
                prefs.edit().remove("output_directory_uri").apply()
                promise.resolve(null)
                return
            }
        }
        promise.resolve(uriStr)
    }

    companion object {
        private const val REQUEST_CODE_OPEN_DOCUMENT_TREE = 4242
    }
}
