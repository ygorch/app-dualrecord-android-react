package com.dualrecordapp.dualcamera

import android.os.Build
import androidx.annotation.RequiresApi
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class DualCameraPackage : ReactPackage {
    @RequiresApi(Build.VERSION_CODES.P)
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(DualCameraEngineModule(reactContext))
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return listOf(DualCameraViewManager())
    }
}
