package com.jetpack.compose.sample_webgpu

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import androidx.core.graphics.createBitmap
import androidx.webgpu.AddressMode
import androidx.webgpu.BackendType
import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.CompositeAlphaMode
import androidx.webgpu.FilterMode
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.GPUSampler
import androidx.webgpu.GPUSamplerBindingLayout
import androidx.webgpu.GPUSamplerDescriptor
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureBindingLayout
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUVertexState
import androidx.webgpu.LoadOp
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.SamplerBindingType
import androidx.webgpu.ShaderStage
import androidx.webgpu.StoreOp
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureSampleType
import androidx.webgpu.TextureUsage
import androidx.webgpu.TextureViewDimension
import androidx.webgpu.helper.WebGpu
import androidx.webgpu.helper.createWebGpu
import com.jetpack.compose.sample_webgpu.ext.toGPUColor
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebGpuRenderer {
    private val BG_COLOR = "#00000000"
    private lateinit var webGpu: WebGpu
    private lateinit var renderPipeline: GPURenderPipeline
    private lateinit var bindGroup: GPUBindGroup

    private var texture: GPUTexture? = null
    private var heightTexture: GPUTexture? = null
    private var sampler: GPUSampler? = null

    suspend fun init(
        bitmap: Bitmap,
        surface: Surface,
        width: Int,
        height: Int
    ) {
        val options = GPURequestAdapterOptions.Builder()
            .setBackendType(BackendType.Vulkan)
            .setCompatibleSurface(null)
            .setForceFallbackAdapter(false)
            .build()

        webGpu = createWebGpu(
            surface = surface,
            requestAdapterOptions = options
        )

        val device = webGpu.device

        Log.d(
            "WebGPU",
            "Adapter backend: ${webGpu.instance.requestAdapter().getInfo().backendType}"
        )

        gpuSurfaceConfigure(width, height)
        initPipeline(device, bitmap)
    }

    private fun gpuSurfaceConfigure(width: Int, height: Int) {
        // Not every adapter supports every alpha compositing mode (e.g. the SwiftShader
        // software adapter used on some emulators rejects Premultiplied), so pick the best
        // transparency-capable mode the adapter actually reports instead of hardcoding one.
        val supportedAlphaModes = webGpu.webgpuSurface.getCapabilities(webGpu.adapter).alphaModes
        val alphaMode = when {
            CompositeAlphaMode.Premultiplied in supportedAlphaModes -> CompositeAlphaMode.Premultiplied
            CompositeAlphaMode.Unpremultiplied in supportedAlphaModes -> CompositeAlphaMode.Unpremultiplied
            else -> CompositeAlphaMode.Auto
        }

        webGpu.webgpuSurface.configure(
            GPUSurfaceConfiguration(
                device = webGpu.device,
                width = width,
                height = height,
                format = TextureFormat.RGBA8Unorm,
                alphaMode = alphaMode
            )
        )
    }

    private fun initPipeline(
        device: GPUDevice,
        bitmap: Bitmap
    ) {
        val logoBitmaps = loadLogoBitmap(bitmap)

        // 1. Upload the logo color + height map to GPU textures.
        val gpuTexture = uploadBitmap(device, logoBitmaps.color)
        texture = gpuTexture

        val gpuHeightTexture = uploadHeightBitmap(device, logoBitmaps.height)
        heightTexture = gpuHeightTexture

        // 2. Create sampler.
        val gpuSampler = device.createSampler(
            GPUSamplerDescriptor(
                addressModeU = AddressMode.ClampToEdge,
                addressModeV = AddressMode.ClampToEdge,
                magFilter = FilterMode.Linear,
                minFilter = FilterMode.Linear
            )
        )
        sampler = gpuSampler

        val shaderCode = """
            struct VertexOutput {
                @builtin(position) position: vec4f,
                @location(0) uv: vec2f,
            }

            @group(0) @binding(0)
            var logoTexture: texture_2d<f32>;

            @group(0) @binding(1)
            var logoSampler: sampler;

            @group(0) @binding(2)
            var heightTexture: texture_2d<f32>;

            @vertex
            fn vs_main(
                @builtin(vertex_index) index: u32
            ) -> VertexOutput {
                var positions = array<vec2f, 6>(
                    vec2f(-0.8,  0.8),
                    vec2f(-0.8, -0.8),
                    vec2f( 0.8,  0.8),
                    vec2f( 0.8,  0.8),
                    vec2f(-0.8, -0.8),
                    vec2f( 0.8, -0.8)
                );

                var uvs = array<vec2f, 6>(
                    vec2f(0.0, 0.0),
                    vec2f(0.0, 1.0),
                    vec2f(1.0, 0.0),
                    vec2f(1.0, 0.0),
                    vec2f(0.0, 1.0),
                    vec2f(1.0, 1.0)
                );

                var output: VertexOutput;
                output.position = vec4f(positions[index], 0.0, 1.0);
                output.uv = uvs[index];
                return output;
            }

            @fragment
            fn fs_main(input: VertexOutput) -> @location(0) vec4f {
                let texel = 1.0 / vec2f(textureDimensions(heightTexture));

                let hL = textureSample(heightTexture, logoSampler, input.uv - vec2f(texel.x, 0.0)).r;
                let hR = textureSample(heightTexture, logoSampler, input.uv + vec2f(texel.x, 0.0)).r;
                let hD = textureSample(heightTexture, logoSampler, input.uv - vec2f(0.0, texel.y)).r;
                let hU = textureSample(heightTexture, logoSampler, input.uv + vec2f(0.0, texel.y)).r;

                // Reconstruct a surface normal from the height map's local slope,
                // so a flat quad reads as a raised, lit 3D relief of the logo.
                let depthScale = 8.0;
                let normal = normalize(vec3f((hL - hR) * depthScale, (hD - hU) * depthScale, 1.0));

                let lightDir = normalize(vec3f(0.4, 0.6, 0.7));
                let viewDir = vec3f(0.0, 0.0, 1.0);
                let halfDir = normalize(lightDir + viewDir);

                let ambient = 0.35;
                let diffuse = max(dot(normal, lightDir), 0.0);
                let lighting = ambient + diffuse * (1.0 - ambient);

                // The logo color can be pure black, so diffuse shading alone (color * lighting)
                // would stay invisible. Add a view-dependent specular highlight on top so the
                // relief still reads even on a flat-black base color.
                let specular = pow(max(dot(normal, halfDir), 0.0), 24.0) * 0.6;

                let color = textureSample(logoTexture, logoSampler, input.uv);
                let shaded = color.rgb * lighting + vec3f(specular) * color.a;
                return vec4f(shaded, color.a);
            }
        """

        val shaderModule = device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(shaderCode)
            )
        )

        val bindGroupLayout = device.createBindGroupLayout(
            GPUBindGroupLayoutDescriptor(
                entries = arrayOf(
                    GPUBindGroupLayoutEntry(
                        binding = 0,
                        visibility = ShaderStage.Fragment,
                        texture = GPUTextureBindingLayout(
                            sampleType = TextureSampleType.Float,
                            viewDimension = TextureViewDimension._2D
                        )
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 1,
                        visibility = ShaderStage.Fragment,
                        sampler = GPUSamplerBindingLayout(
                            type = SamplerBindingType.Filtering
                        )
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 2,
                        visibility = ShaderStage.Fragment,
                        texture = GPUTextureBindingLayout(
                            sampleType = TextureSampleType.Float,
                            viewDimension = TextureViewDimension._2D
                        )
                    )
                )
            )
        )

        val pipelineLayout = device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(
                bindGroupLayouts = arrayOf(bindGroupLayout)
            )
        )

        bindGroup = device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bindGroupLayout,
                entries = arrayOf(
                    GPUBindGroupEntry(
                        binding = 0,
                        textureView = gpuTexture.createView()
                    ),
                    GPUBindGroupEntry(
                        binding = 1,
                        sampler = gpuSampler
                    ),
                    GPUBindGroupEntry(
                        binding = 2,
                        textureView = gpuHeightTexture.createView()
                    )
                )
            )
        )

        renderPipeline = device.createRenderPipeline(
            GPURenderPipelineDescriptor(
                vertex = GPUVertexState(
                    module = shaderModule,
                    entryPoint = "vs_main"
                ),
                layout = pipelineLayout,
                primitive = GPUPrimitiveState(
                    topology = PrimitiveTopology.TriangleList
                ),
                fragment = GPUFragmentState(
                    module = shaderModule,
                    entryPoint = "fs_main",
                    targets = arrayOf(
                        GPUColorTargetState(
                            format = TextureFormat.RGBA8Unorm,
                            blend = GPUBlendState(
                                color = GPUBlendComponent(
                                    operation = BlendOperation.Add,
                                    srcFactor = BlendFactor.SrcAlpha,
                                    dstFactor = BlendFactor.OneMinusSrcAlpha
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private fun uploadBitmap(
        device: GPUDevice,
        source: Bitmap
    ): GPUTexture {
        // Normalize to ARGB_8888 so each pixel is 4 bytes.
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, false)
        val width = bitmap.width
        val height = bitmap.height

        val gpuTexture = device.createTexture(
            GPUTextureDescriptor(
                usage = TextureUsage.TextureBinding or
                        TextureUsage.CopyDst,
                size = GPUExtent3D(width, height, 1),
                format = TextureFormat.RGBA8Unorm
            )
        )

        // Android Bitmap pixels are native-endian ARGB_8888 integers.
        // Convert to explicit RGBA byte order for RGBA8Unorm.
        val argbPixels = IntArray(width * height)
        bitmap.getPixels(
            argbPixels, 0, width, 0, 0, width, height
        )

        val rgbaBytes = ByteBuffer.allocateDirect(
            width * height * 4
        ).order(ByteOrder.nativeOrder())

        for (pixel in argbPixels) {
            rgbaBytes.put(((pixel shr 16) and 0xFF).toByte())
            rgbaBytes.put(((pixel shr 8) and 0xFF).toByte())
            rgbaBytes.put((pixel and 0xFF).toByte())
            rgbaBytes.put(((pixel ushr 24) and 0xFF).toByte())
        }
        rgbaBytes.flip()

        device.queue.writeTexture(
            GPUTexelCopyTextureInfo(texture = gpuTexture),
            rgbaBytes,
            GPUExtent3D(width, height, 1),
            GPUTexelCopyBufferLayout(
                offset = 0,
                bytesPerRow = width * 4,
                rowsPerImage = height
            )
        )

        bitmap.recycle()
        return gpuTexture
    }

    private fun uploadHeightBitmap(
        device: GPUDevice,
        source: Bitmap
    ): GPUTexture {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, false)
        val width = bitmap.width
        val height = bitmap.height

        val gpuTexture = device.createTexture(
            GPUTextureDescriptor(
                usage = TextureUsage.TextureBinding or
                        TextureUsage.CopyDst,
                size = GPUExtent3D(width, height, 1),
                format = TextureFormat.R8Unorm
            )
        )

        val argbPixels = IntArray(width * height)
        bitmap.getPixels(
            argbPixels, 0, width, 0, 0, width, height
        )

        // Height map is grayscale, so only the red channel is needed.
        val rBytes = ByteBuffer.allocateDirect(width * height)
            .order(ByteOrder.nativeOrder())

        for (pixel in argbPixels) {
            rBytes.put(((pixel shr 16) and 0xFF).toByte())
        }
        rBytes.flip()

        device.queue.writeTexture(
            GPUTexelCopyTextureInfo(texture = gpuTexture),
            rBytes,
            GPUExtent3D(width, height, 1),
            GPUTexelCopyBufferLayout(
                offset = 0,
                bytesPerRow = width,
                rowsPerImage = height
            )
        )

        bitmap.recycle()
        return gpuTexture
    }

    fun render() {
        if (!::webGpu.isInitialized ||
            !::renderPipeline.isInitialized
        ) return

        val gpu = webGpu
        val surfaceTexture = gpu.webgpuSurface.getCurrentTexture()
        val commandEncoder = gpu.device.createCommandEncoder()

        val renderPass = commandEncoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        BG_COLOR.toGPUColor(),
                        surfaceTexture.texture.createView(),
                        loadOp = LoadOp.Clear,
                        storeOp = StoreOp.Store
                    )
                )
            )
        )

        renderPass.setPipeline(renderPipeline)
        renderPass.setBindGroup(0, bindGroup)
        renderPass.draw(6)
        renderPass.end()

        gpu.device.queue.submit(
            arrayOf(commandEncoder.finish())
        )
        gpu.webgpuSurface.present()
    }

    fun cleanup() {
        texture?.close()
        heightTexture?.close()
        sampler?.close()

        if (::webGpu.isInitialized) {
            webGpu.close()
        }
    }

    private data class LogoBitmaps(val color: Bitmap, val height: Bitmap)

    private fun loadLogoBitmap(bitmap: Bitmap): LogoBitmaps {
        return LogoBitmaps(bitmap, createHeightMap(bitmap))
    }

    // Builds a grayscale relief map used as the depth/elevation source for the fragment
    // shader. Bitmaps with real transparency (e.g. a cutout logo PNG) use their alpha
    // channel as the silhouette. Opaque bitmaps (e.g. a photo picked from the gallery,
    // which has alpha = 255 everywhere) have no transparency to read depth from, so we
    // fall back to luminance: brighter pixels bulge toward the viewer, darker pixels
    // recede. A few passes of box blur then round hard edges into a smooth relief
    // instead of a flat plateau.
    private fun createHeightMap(colorBitmap: Bitmap): Bitmap {
        val width = colorBitmap.width
        val height = colorBitmap.height

        val pixels = IntArray(width * height)
        colorBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val hasTransparency = pixels.any { ((it ushr 24) and 0xFF) < 255 }

        var heights = FloatArray(width * height) { i ->
            val pixel = pixels[i]
            if (hasTransparency) {
                ((pixel ushr 24) and 0xFF) / 255f
            } else {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }
        }

        val blurRadius = (minOf(width, height) / 20).coerceIn(2, 12)
        repeat(3) {
            heights = boxBlur(heights, width, height, blurRadius)
        }

        val outPixels = IntArray(width * height) { i ->
            val value = (heights[i].coerceIn(0f, 1f) * 255f).toInt()
            android.graphics.Color.argb(255, value, value, value)
        }

        return createBitmap(width, height).also { bitmap ->
            bitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        }
    }

    private fun boxBlur(input: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val horizontal = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (dx in -radius..radius) {
                    val sx = x + dx
                    if (sx in 0 until width) {
                        sum += input[row + sx]
                        count++
                    }
                }
                horizontal[row + x] = sum / count
            }
        }

        val output = FloatArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    val sy = y + dy
                    if (sy in 0 until height) {
                        sum += horizontal[sy * width + x]
                        count++
                    }
                }
                output[y * width + x] = sum / count
            }
        }
        return output
    }
}