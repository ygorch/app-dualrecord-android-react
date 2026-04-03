package com.dualrecordapp.dualcamera

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap

class DualCameraEngineModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val captureManager = DualCameraCaptureManager(reactContext)

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
}
