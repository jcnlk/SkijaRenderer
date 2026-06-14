package com.github.jcnlk.skijarenderer.skia

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.jetbrains.skija.BackendRenderTarget
import org.jetbrains.skija.ColorAlphaType
import org.jetbrains.skija.ColorSpace
import org.jetbrains.skija.ColorType
import org.jetbrains.skija.DirectContext
import org.jetbrains.skija.ImageInfo
import org.jetbrains.skija.Surface
import org.jetbrains.skija.SurfaceColorFormat
import org.jetbrains.skija.SurfaceOrigin
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL33C
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

class SkijaPIP: PictureInPictureRenderer<SkijaPIP.SkijaRenderState>() {
    private val rasterTargets = mutableMapOf<Int, RasterTarget>()

    private var renderTarget: BackendRenderTarget? = null
    private var context: DirectContext? = null
    private var glSurface: Surface? = null

    private var fbo = 0
    private var depthStencil = 0
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var lastTextureId = 0

    override fun getTranslateY(height: Int, guiScale: Int) = height / 2f
    override fun getRenderStateClass() = SkijaRenderState::class.java
    override fun getTextureLabel(): String = "skijarenderer"

    override fun prepare(
        state: SkijaRenderState,
        guiRenderState: GuiRenderState,
        featureRenderDispatcher: FeatureRenderDispatcher,
        guiScale: Int
    ) {
        val window = Minecraft.getInstance().window
        if (window.isIconified) return

        val textureScale = textureScale(state, guiScale)
        val width = ceil(state.width * textureScale).toInt().coerceAtLeast(1)
        val height = ceil(state.height * textureScale).toInt().coerceAtLeast(1)
        val now = System.nanoTime()

        val target = rasterTargets.getOrPut(state.targetKey) { RasterTarget() }
        target.lastUsedNanos = now
        pruneRasterTargets(now, state.targetKey)
        val view = textureFor(target, width, height) ?: return
        val currentTexture = target.texture ?: return
        val glTextureId = (currentTexture as? GlTexture)?.glId()
        val canReuseRasterFrame = glTextureId == null
            && target.lastRasterWidth == width
            && target.lastRasterHeight == height
            && state.cacheReusable
            && state.cacheToken != null
            && state.cacheToken == target.lastRasterCacheToken

        if (!canReuseRasterFrame) {
            if (glTextureId != null) {
                renderSkijaToGlTexture(state, width, height, glTextureId, textureScale)
            }
            else {
                closeGlTarget()
                renderSkijaToImage(target, state, width, height, textureScale)
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(currentTexture, target.image ?: return)
                target.lastRasterWidth = width
                target.lastRasterHeight = height
                target.lastRasterCacheToken = state.cacheToken
                target.lastRasterUploadNanos = now
            }
        }

        guiRenderState.addBlitToCurrentLayer(
            BlitRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(
                    view,
                    RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
                ),
                state.pose(),
                state.x0(),
                state.y0(),
                state.x1(),
                state.y1(),
                0f,
                1f,
                1f,
                0f,
                -1,
                state.scissorArea(),
                state.bounds()
            )
        )
    }

    override fun renderToTexture(state: SkijaRenderState, poseStack: PoseStack, submitNodeCollector: SubmitNodeCollector) = Unit

    private fun renderSkijaToImage(target: RasterTarget, state: SkijaRenderState, width: Int, height: Int, dpr: Float) {
        val skijaSurface = surfaceFor(target, width, height)
        skijaSurface.canvas.clear(0)

        val canvas = skijaSurface.canvas
        val flipSaveCount = canvas.save()
        try {
            canvas.translate(0f, height.toFloat())
            canvas.scale(1f, -1f)

            Skija.beginFrame(canvas, width.toFloat(), height.toFloat(), dpr)
            Skija.push()
            Skija.translate(-state.bounds.left(), -state.bounds.top())
            Skija.transform(state.poseMatrix)
            state.callback.run()
            Skija.pop()
            Skija.endFrame()
        }
        finally {
            canvas.restoreToCount(flipSaveCount)
        }
    }

    private fun renderSkijaToGlTexture(state: SkijaRenderState, width: Int, height: Int, colorTexId: Int, dpr: Float) {
        rasterTargets.values.forEach(::closeRasterTarget)

        val previousFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING)
        val previousViewport = IntArray(4)
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, previousViewport)

        bindTarget(colorTexId, width, height)

        GlStateManager._viewport(0, 0, width, height)
        GL33C.glBindSampler(0, 0)

        val directContext = context ?: DirectContext.makeGL().also { context = it }
        directContext.resetGLAll()

        val skijaSurface = glSurfaceFor(width, height, colorTexId)
        skijaSurface.canvas.clear(0)

        Skija.beginFrame(skijaSurface.canvas, width.toFloat(), height.toFloat(), dpr)
        Skija.push()
        Skija.translate(-state.bounds.left(), -state.bounds.top())
        Skija.transform(state.poseMatrix)
        state.callback.run()
        Skija.pop()
        Skija.endFrame()

        skijaSurface.flushAndSubmit()
        directContext.flush().submit(true)

        GL30C.glBindVertexArray(0)
        GL30C.glUseProgram(0)

        GlStateManager._disableDepthTest()
        GlStateManager._disableCull()
        GlStateManager._enableBlend(0)
        GlStateManager._blendFuncSeparate(770, 771, 1, 0)

        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previousFbo)
        GlStateManager._viewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
    }

    private fun textureFor(target: RasterTarget, width: Int, height: Int): GpuTextureView? {
        val existingTexture = target.texture
        val existingView = target.textureView
        if (existingTexture != null && existingView != null && existingTexture.getWidth(0) == width && existingTexture.getHeight(0) == height) {
            return existingView
        }

        closeGpuTarget(target)

        val createdTexture = RenderSystem.getDevice().createTexture(
            getTextureLabel(),
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
            GpuFormat.RGBA8_UNORM,
            width,
            height,
            1,
            1
        )
        val createdView = RenderSystem.getDevice().createTextureView(createdTexture)

        target.texture = createdTexture
        target.textureView = createdView
        return createdView
    }

    private fun bindTarget(colorTexId: Int, width: Int, height: Int) {
        if (fbo == 0) fbo = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
        GlStateManager._glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colorTexId, 0)

        if (depthStencil == 0 || attachedWidth != width || attachedHeight != height) {
            if (depthStencil != 0) GL30C.glDeleteRenderbuffers(depthStencil)
            depthStencil = GL30C.glGenRenderbuffers()
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depthStencil)
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH24_STENCIL8, width, height)
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0)
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, depthStencil)
            attachedWidth = width
            attachedHeight = height
        }
    }

    private fun glSurfaceFor(width: Int, height: Int, textureId: Int): Surface {
        val existing = glSurface
        if (existing != null && existing.width == width && existing.height == height && lastTextureId == textureId) {
            return existing
        }

        glSurface?.close()
        renderTarget?.close()

        val directContext = context ?: DirectContext.makeGL().also { context = it }
        val target = BackendRenderTarget.makeGL(width, height, 0, 8, fbo, GL30C.GL_RGBA8)
        val created = Surface.makeFromBackendRenderTarget(
            directContext,
            target,
            SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,
            ColorSpace.getSRGB()
        )

        renderTarget = target
        glSurface = created
        lastTextureId = textureId
        return created
    }

    private fun surfaceFor(target: RasterTarget, width: Int, height: Int): Surface {
        val existingImage = target.image
        val existingSurface = target.surface
        if (existingImage != null && existingSurface != null && existingImage.getWidth() == width && existingImage.getHeight() == height) {
            return existingSurface
        }

        closeRasterTarget(target)

        val createdImage = NativeImage(width, height, false)
        val imageInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.getSRGB())
        val createdSurface = Surface.makeRasterDirect(imageInfo, createdImage.getPointer(), width.toLong() * imageInfo.bytesPerPixel)

        target.image = createdImage
        target.surface = createdSurface
        return createdSurface
    }

    private fun pruneRasterTargets(now: Long, keepKey: Int) {
        val idleCutoff = now - VULKAN_TARGET_IDLE_NANOS
        val iterator = rasterTargets.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != keepKey && entry.value.lastUsedNanos < idleCutoff) {
                closeGpuTarget(entry.value)
                closeRasterTarget(entry.value)
                iterator.remove()
            }
        }

        while (rasterTargets.size > VULKAN_MAX_RASTER_TARGETS && rasterTargets.size > 1) {
            val oldest = rasterTargets
                .filterKeys { it != keepKey }
                .minByOrNull { it.value.lastUsedNanos }
                ?: break
            closeGpuTarget(oldest.value)
            closeRasterTarget(oldest.value)
            rasterTargets.remove(oldest.key)
        }
    }

    override fun close() {
        rasterTargets.values.forEach { target ->
            closeGpuTarget(target)
            closeRasterTarget(target)
        }
        rasterTargets.clear()
        closeGlTarget()
        super.close()
    }

    private fun closeGpuTarget(target: RasterTarget) {
        target.textureView?.close()
        target.textureView = null
        target.texture?.close()
        target.texture = null
        target.lastRasterWidth = 0
        target.lastRasterHeight = 0
        target.lastRasterCacheToken = null
        target.lastRasterUploadNanos = 0L
    }

    private fun closeRasterTarget(target: RasterTarget) {
        target.surface?.close()
        target.surface = null
        target.image?.close()
        target.image = null
    }

    private fun closeGlTarget() {
        glSurface?.close()
        glSurface = null
        renderTarget?.close()
        renderTarget = null
        context?.close()
        context = null
        lastTextureId = 0

        if (fbo != 0) {
            GlStateManager._glDeleteFramebuffers(fbo)
            fbo = 0
        }
        if (depthStencil != 0) {
            GL30C.glDeleteRenderbuffers(depthStencil)
            depthStencil = 0
        }
        attachedWidth = 0
        attachedHeight = 0
    }

    private class RasterTarget {
        var texture: GpuTexture? = null
        var textureView: GpuTextureView? = null
        var image: NativeImage? = null
        var surface: Surface? = null
        var lastRasterUploadNanos = 0L
        var lastRasterWidth = 0
        var lastRasterHeight = 0
        var lastRasterCacheToken: Int? = null
        var lastUsedNanos = 0L
    }

    data class SkijaRenderState(
        val width: Int,
        val height: Int,
        val poseMatrix: Matrix3x2f,
        private val scissor: ScreenRectangle?,
        val bounds: ScreenRectangle,
        val callback: Runnable,
        val cacheToken: Int?,
        val targetKey: Int,
        val cacheReusable: Boolean
    ): PictureInPictureRenderState {
        override fun x0() = bounds.left()
        override fun y0() = bounds.top()
        override fun x1() = bounds.right()
        override fun y1() = bounds.bottom()
        override fun scissorArea() = scissor
        override fun bounds() = bounds
        override fun scale() = 1f
        override fun pose(): Matrix3x2fc = PictureInPictureRenderState.IDENTITY_POSE
    }

    companion object {
        private const val VULKAN_MAX_RASTER_PIXELS = 4_000_000f
        private const val VULKAN_MAX_RASTER_TARGETS = 12
        private const val VULKAN_TARGET_IDLE_NANOS = 1_000_000_000L
        private const val VULKAN_ANIMATED_RASTER_SCALE = 2.25f
        private val drawSlotCounters = WeakHashMap<GuiGraphicsExtractor, DrawSlotCounter>()

        @JvmStatic
        fun GuiGraphicsExtractor.drawSkija(callback: Runnable) {
            drawSkija(0f, 0f, guiWidth().toFloat(), guiHeight().toFloat(), callback)
        }

        @JvmStatic
        fun GuiGraphicsExtractor.drawSkija(x: Number, y: Number, width: Number, height: Number, callback: Runnable) {
            drawSkija(x, y, width, height, null, callback)
        }

        @JvmStatic
        fun GuiGraphicsExtractor.drawSkija(
            x: Number,
            y: Number,
            width: Number,
            height: Number,
            cacheToken: Int?,
            callback: Runnable
        ) {
            drawSkija(x, y, width, height, null, cacheToken, callback)
        }

        @JvmStatic
        fun GuiGraphicsExtractor.drawSkijaCached(
            cacheKey: Any,
            x: Number,
            y: Number,
            width: Number,
            height: Number,
            cacheToken: Int?,
            callback: Runnable
        ) {
            drawSkija(x, y, width, height, cacheKey, cacheToken, callback)
        }

        private fun GuiGraphicsExtractor.drawSkija(
            x: Number,
            y: Number,
            width: Number,
            height: Number,
            cacheKey: Any?,
            cacheToken: Int?,
            callback: Runnable
        ) {
            val window = Minecraft.getInstance().window
            if (window.isIconified || window.guiScaledWidth <= 0 || window.guiScaledHeight <= 0) return

            val pose = Matrix3x2f(pose())
            val scissor = scissorStack.peek()
            val left = floor(x.toFloat()).toInt()
            val top = floor(y.toFloat()).toInt()
            val right = ceil(x.toFloat() + width.toFloat()).toInt()
            val bottom = ceil(y.toFloat() + height.toFloat()).toInt()
            val renderRect = ScreenRectangle(left, top, right - left, bottom - top).transformMaxBounds(pose)
            if (renderRect.width() <= 0 || renderRect.height() <= 0) return

            val bounds = if (scissor != null) {
                scissor.intersection(renderRect) ?: return
            }
            else {
                renderRect
            }
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val state = SkijaRenderState(
                bounds.width(),
                bounds.height(),
                pose,
                scissor,
                bounds,
                callback,
                cacheToken,
                targetKey(bounds, cacheKey, nextDrawSlotKey()),
                cacheKey != null
            )
            this.guiRenderState.addPicturesInPictureState(state)
        }

        private fun targetKey(bounds: ScreenRectangle, cacheKey: Any?, drawSlotKey: Int): Int {
            var result = cacheKey?.hashCode() ?: drawSlotKey
            result = 31 * result + bounds.top()
            result = 31 * result + bounds.left()
            result = 31 * result + bounds.width()
            result = 31 * result + bounds.height()
            return result
        }

        private fun GuiGraphicsExtractor.nextDrawSlotKey(): Int {
            val counter = drawSlotCounters.getOrPut(this) { DrawSlotCounter() }
            return counter.nextSlot ++
        }

        private fun textureScale(state: SkijaRenderState, guiScale: Int): Float {
            val nativeScale = guiScale.toFloat().coerceAtLeast(1f)
            val backend = runCatching { RenderSystem.getDevice().getDeviceInfo().backendName() }.getOrDefault("")
            if (!backend.contains("Vulkan", ignoreCase = true)) return nativeScale

            val preferredScale = if (state.cacheToken != null && state.cacheToken != 0) {
                nativeScale.coerceAtMost(VULKAN_ANIMATED_RASTER_SCALE)
            }
            else {
                nativeScale
            }
            val nativePixels = state.width.toFloat() * state.height.toFloat() * preferredScale * preferredScale
            if (!nativePixels.isFinite() || nativePixels <= VULKAN_MAX_RASTER_PIXELS) return preferredScale

            return (preferredScale * sqrt(VULKAN_MAX_RASTER_PIXELS / nativePixels)).coerceIn(0.5f, preferredScale)
        }

        private class DrawSlotCounter {
            var nextSlot = 0
        }
    }
}