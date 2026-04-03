package com.dualrecordapp.dualcamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.P)
class DualCameraView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {

    private val textureView: TextureView = TextureView(context)
    private var activeLensId: String? = null
    private var isSecondary: Boolean = false

    init {
        textureView.surfaceTextureListener = this
        addView(textureView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    fun setActiveLensId(id: String?) {
        this.activeLensId = id
        DualCameraCaptureManager.getInstance(context).updateLensSelection(isSecondary, id)
    }

    fun setIsSecondary(secondary: Boolean) {
        this.isSecondary = secondary
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        DualCameraCaptureManager.getInstance(context).setPreviewSurface(surface, width, height, isSecondary, activeLensId)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // Handle size changes
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        DualCameraCaptureManager.getInstance(context).removePreviewSurface(isSecondary)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Invoked when new frames arrive
    }
}
