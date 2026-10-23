// 背景模糊宿主：移植自 HyperIsland 3.2.1（Apache-2.0）的 BlurBars.kt
package com.vpnautoswitch.module.ui.hyper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalRootBottomBarPadding = staticCompositionLocalOf { 0.dp }
internal val LocalBarBlurEnabled = staticCompositionLocalOf { false }
internal val LocalBarBlurBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 把页面内容录进 layer backdrop，底栏 / 顶栏才能对它做真正的模糊与折射。
 * 不支持 RuntimeShader 的设备会自动退化成半透明纯色。
 */
@Composable
internal fun BarBlurHost(
    enabled: Boolean,
    captureForEffects: Boolean = false,
    liquidGlassEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = if ((enabled || captureForEffects || liquidGlassEnabled) && isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
    CompositionLocalProvider(
        LocalBarBlurEnabled provides enabled,
        LocalBarBlurBackdrop provides backdrop,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
internal fun BarBackdropContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalBarBlurBackdrop.current
    Box(
        modifier = modifier.then(
            if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
        ),
    ) {
        content()
    }
}

@Composable
internal fun BlurredBar(
    topGradient: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.barBlurBackground(
            shape = RectangleShape,
            topGradient = topGradient,
        ),
    ) {
        content()
    }
}

@Composable
internal fun Modifier.barBlurBackground(
    shape: Shape,
    topGradient: Boolean = false,
): Modifier {
    val backdrop = LocalBarBlurBackdrop.current
    val blurEnabled = LocalBarBlurEnabled.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val contentColor = MiuixTheme.colorScheme.onSurface
    return if (blurEnabled && backdrop != null) {
        val blendColors = if (topGradient) {
            listOf(BlendColorEntry(color = surfaceColor.copy(alpha = TOP_BAR_SURFACE_ALPHA)))
        } else {
            listOf(
                BlendColorEntry(color = surfaceColor.copy(alpha = BOTTOM_BAR_SURFACE_ALPHA)),
                BlendColorEntry(color = contentColor.copy(alpha = BAR_GLASS_TINT_ALPHA)),
            )
        }
        val blurColors = BlurDefaults.blurColors(blendColors = blendColors)
        if (topGradient) {
            progressiveTextureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = BAR_BLUR_RADIUS,
                gradient = TOP_BAR_PROGRESSIVE_BLUR,
                colors = blurColors,
            )
        } else {
            textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = BAR_BLUR_RADIUS,
                colors = blurColors,
            )
        }
    } else {
        background(color = surfaceColor, shape = shape)
    }
}

private val TOP_BAR_PROGRESSIVE_BLUR = ProgressiveBlur.Top.copy(
    startFraction = 0.12f,
    endFraction = 1f,
    curve = 1.25f,
)
private const val BAR_BLUR_RADIUS = 16f
private const val TOP_BAR_SURFACE_ALPHA = 0.66f
private const val BOTTOM_BAR_SURFACE_ALPHA = 0.58f
private const val BAR_GLASS_TINT_ALPHA = 0.025f
