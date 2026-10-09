package com.jetpack.compose.sample_webgpu.ext

import androidx.webgpu.GPUColor

fun String.toGPUColor(): GPUColor {
    val hex = removePrefix("#")

    require(hex.length == 6 || hex.length == 8) {
        "Hex color must be #RRGGBB or #AARRGGBB"
    }

    val color = hex.toLong(16)

    val (a, r, g, b) = if (hex.length == 8) {
        listOf(
            (color shr 24) and 0xFF,
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF
        )
    } else {
        listOf(
            255L,
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF
        )
    }

    return GPUColor(
        r / 255.0,
        g / 255.0,
        b / 255.0,
        a / 255.0
    )
}