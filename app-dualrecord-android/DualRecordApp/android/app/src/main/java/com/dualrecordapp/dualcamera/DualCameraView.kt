package com.dualrecordapp.dualcamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.widget.FrameLayout

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
        // Restart preview or re-bind camera with new lens ID
    }

    fun setIsSecondary(secondary: Boolean) {
        this.isSecondary = secondary
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // Here we would typically pass the Surface down to the DualCameraCaptureManager
        // For example: captureManager.addPreviewSurface(Surface(surface), isSecondary)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // Handle size changes
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Invoked when new frames arrive
    }
}
