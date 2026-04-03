package com.dualrecordapp.dualcamera

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class DualCameraViewManager : SimpleViewManager<DualCameraView>() {

    override fun getName(): String {
        return "DualCameraView"
    }

    override fun createViewInstance(reactContext: ThemedReactContext): DualCameraView {
        return DualCameraView(reactContext)
    }

    @ReactProp(name = "activeLensId")
    fun setActiveLensId(view: DualCameraView, activeLensId: String?) {
        view.setActiveLensId(activeLensId)
    }

    @ReactProp(name = "isSecondary")
    fun setIsSecondary(view: DualCameraView, isSecondary: Boolean) {
        view.setIsSecondary(isSecondary)
    }
}
