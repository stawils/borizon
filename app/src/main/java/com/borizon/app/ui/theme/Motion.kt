package com.borizon.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally

/**
 * Centralized motion constants and pre-built transition specs.
 * All durations in milliseconds.
 */
object BorizonMotion {
    // ── Durations ────────────────────────────────────────────────
    const val DurationFast = 150
    const val DurationNormal = 300
    const val DurationSlow = 500
    const val DurationContemplative = 800

    // ── Easings ──────────────────────────────────────────────────
    val EaseOutWarm = EaseOutCubic
    val EaseInWarm = EaseInCubic
    val EaseInOutGentle = EaseInOutSine

    // ── Stagger ──────────────────────────────────────────────────
    const val StaggerStep = 80

    // ── Pre-built transitions ────────────────────────────────────
    val ContentEnterTransition: EnterTransition = slideInVertically(
        animationSpec = tween(DurationNormal, easing = EaseOutWarm),
        initialOffsetY = { it / 4 }
    ) + fadeIn(tween(DurationNormal, easing = EaseOutWarm))

    val ContentExitTransition: ExitTransition = fadeOut(tween(DurationFast, easing = EaseInWarm))

    val CardEnterTransition: EnterTransition = slideInVertically(
        animationSpec = tween(DurationSlow, easing = EaseOutWarm),
        initialOffsetY = { it / 8 }
    ) + fadeIn(tween(DurationSlow, easing = EaseOutWarm))

    // Navigation transitions
    val NavEnterTransition: EnterTransition = slideInHorizontally(
        animationSpec = tween(DurationSlow, easing = EaseInOutGentle),
        initialOffsetX = { it }
    ) + fadeIn(tween(DurationSlow, easing = EaseOutWarm))

    val NavExitTransition: ExitTransition = slideOutHorizontally(
        animationSpec = tween(DurationSlow, easing = EaseInOutGentle),
        targetOffsetX = { -(it / 3) }
    ) + fadeOut(tween(DurationNormal, easing = EaseInWarm))

    val NavPopEnterTransition: EnterTransition = slideInHorizontally(
        animationSpec = tween(DurationSlow, easing = EaseInOutGentle),
        initialOffsetX = { -(it / 3) }
    ) + fadeIn(tween(DurationSlow, easing = EaseOutWarm))

    val NavPopExitTransition: ExitTransition = slideOutHorizontally(
        animationSpec = tween(DurationSlow, easing = EaseInOutGentle),
        targetOffsetX = { it }
    ) + fadeOut(tween(DurationNormal, easing = EaseInWarm))
}
