package com.jetpack.compose.sample_webgpu

import android.graphics.Bitmap
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WebGpuSurface(bitmap: Bitmap, modifier: Modifier = Modifier) {
    LoadSurface(bitmap, modifier)
}

@Composable
fun LoadSurface(bitmap: Bitmap, modifier: Modifier = Modifier) {
    // Create and remember a WebGpuRenderer instance.
    val renderer = remember { WebGpuRenderer() }
    AndroidEmbeddedExternalSurface(
        modifier = modifier,
        isOpaque = false
    ) {
        onSurface { surface, width, height ->
            withContext(Dispatchers.IO) {
                try {
                    renderer.init(bitmap, surface, width, height)
                    renderer.render()
                } finally {
                    renderer.cleanup()
                }
            }
        }
    }
}