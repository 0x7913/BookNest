package com.booknest.reader.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

private val spatial = spring<androidx.compose.ui.unit.IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/** Material 3 共享轴（X）：书库 → 阅读 / 目录 → 章节，保持空间连续性。 */
fun sharedAxisX(forward: Boolean): ContentTransform =
    (fadeIn(tween(170)) + slideInHorizontally(spatial) { w -> if (forward) w / 5 else -w / 5 })
        .togetherWith(fadeOut(tween(150)) + slideOutHorizontally(spatial) { w -> if (forward) -w / 5 else w / 5 })

/** 共享轴（Y）：统计 / 设置等次级页面。 */
fun sharedAxisY(forward: Boolean): ContentTransform =
    (fadeIn(tween(170)) + slideInVertically(spatial) { h -> if (forward) h / 6 else -h / 6 })
        .togetherWith(fadeOut(tween(150)) + slideOutVertically(spatial) { h -> if (forward) -h / 6 else h / 6 })

/**
 * 工具栏 / 面板的显隐。
 * 之前用 DampingRatioLowBouncy 的弹簧，面板出现时会「回弹一下」——用户明确要求不要晃，
 * 这里改成纯 tween 缓动，落位即静止，不产生任何过冲。
 */
fun springSheetEnter() = fadeIn(tween(150)) + slideInVertically(
    animationSpec = tween(230, easing = FastOutSlowInEasing)
) { it }

fun springSheetExit() = fadeOut(tween(110)) + slideOutVertically(
    animationSpec = tween(170, easing = FastOutLinearInEasing)
) { it / 4 }

fun coverPageEnter() = fadeIn(tween(110)) + slideInVertically(
    animationSpec = tween(240, easing = FastOutSlowInEasing)
) { it / 8 }

fun coverPageExit() = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 10 }
