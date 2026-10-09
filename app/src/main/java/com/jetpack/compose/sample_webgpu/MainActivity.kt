package com.jetpack.compose.sample_webgpu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.cos

// Matches WebGpuRenderer.BG_COLOR so the container around the generated surface
// doesn't show a seam/mismatch against the WebGPU render pass's clear color.
private val WEBGPU_BG_COLOR = Color.White
private val OBJECT_SHADOW_COLOR = Color(0xFF454545)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WebImageUploader()
        }
    }
}

@Composable
fun WebImageUploader() {
    val context = LocalContext.current

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var showObjectShadow by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedImageUri = uri
        bitmap = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            Column {
                // Header
                Text(
                    text = "WebGPU 3D Model Generator",
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                // Upload Image
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { imagePicker.launch("image/*") }
                        .border(
                            width = 1.dp,
                            color = Color.Gray,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Upload Image", color = Color.Gray)
                }
            }
        }

        // Generate Button
        Button(
            onClick = {
                selectedImageUri?.let { uri ->
                    val bitmapGenerated = context.contentResolver
                        .openInputStream(uri)
                        ?.use { inputStream ->
                            BitmapFactory.decodeStream(inputStream)
                        }

                    bitmap = bitmapGenerated
                }
            },
            enabled = selectedImageUri != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate 3D model")
        }

        // Display two images in a horizontal row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Image 1: Uploaded image
            selectedImageUri?.let { uri ->
                Column(
                    modifier = Modifier.fillMaxWidth(0.25f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = Color.Gray,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(5.dp)
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = "Uploaded image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Text(
                        text = "Uploaded image",
                        modifier = Modifier.padding(top = 8.dp),
                        color = Color.Black,
                        fontSize = 10.sp
                    )
                }
            }

            // Image 2: Second image
            bitmap?.let { bitmap ->
                Column(
                    modifier = Modifier.weight(3f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(WEBGPU_BG_COLOR)
                            .border(
                                width = 1.dp,
                                color = Color.Gray,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showObjectShadow) {
                            val shadowWidthScale = 0.35f +
                                0.65f * abs(cos(Math.toRadians(rotationAngle.toDouble()))).toFloat()

                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.tint(OBJECT_SHADOW_COLOR),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        rotationX = 45f
                                        clip = true
                                        scaleX = shadowWidthScale
                                        scaleY = 0.4f
                                        alpha = 0.45f
                                        translationY = size.height * 0.3f
                                    }
                            )
                        }

                        Rotating360Image(
                            rotationY = rotationAngle,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            WebGpuSurface(
                                bitmap = bitmap,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text(
                        text = "Generated 3D depth image",
                        modifier = Modifier.padding(top = 8.dp),
                        color = Color.Black
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = showObjectShadow,
                            onCheckedChange = { showObjectShadow = it }
                        )
                        Text("Add object shadow", color = Color.Black)
                    }
                    Slider(
                        value = rotationAngle,
                        onValueChange = { rotationAngle = it },
                        valueRange = 0f..360f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun Rotating360Image(
    rotationY: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.graphicsLayer {
            this.rotationY = rotationY
            cameraDistance = 12f * density
            clip = true
        }
    ) {
        content()
    }
}