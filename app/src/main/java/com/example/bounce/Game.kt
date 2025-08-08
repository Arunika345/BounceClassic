package com.example.bounce

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.icons.Icons
import androidx.compose.material3.icons.filled.Pause
import androidx.compose.material3.icons.filled.PlayArrow
import androidx.compose.material3.icons.filled.Replay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ===== Math & helpers =====
private data class Vec(var x: Float, var y: Float)

private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

// ===== Game constants =====
private const val GRAVITY = 22.5f     // world units / s^2
private const val MAX_FALL = 35f
private const val MOVE_ACCEL = 55f    // accel when hold left/right
private const val AIR_ACCEL = 30f
private const val FRICTION = 40f      // ground friction
private const val JUMP_VELOCITY = 12.5f
private const val BOUNCE = 0.25f      // energy kept when bonk wall/ceiling
private const val BALL_RADIUS = 0.42f // in tiles
private const val TILE = 1f           // world tile unit

// tile types
private const val T_EMPTY = '.'
private const val T_SOLID = '#'
private const val T_SPAWN = 'S'
private const val T_EXIT  = 'E'
private const val T_SPIKE = '^'

@Composable
fun GameScreen() {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    var lives by remember { mutableStateOf(3) }
    var levelIndex by remember { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }

    // Load level
    var level by remember(levelIndex) { mutableStateOf(Levels.load(levelIndex)) }

    // Player state
    var pos by remember(levelIndex) { mutableStateOf(Vec(level.spawn.x, level.spawn.y)) }
    var vel by remember { mutableStateOf(Vec(0f, 0f)) }
    var onGround by remember { mutableStateOf(false) }

    // Controls state (held)
    val leftIS = remember { MutableInteractionSource() }
    val rightIS = remember { MutableInteractionSource() }
    val jumpIS = remember { MutableInteractionSource() }

    val leftHeld by leftIS.collectIsPressedAsState()
    val rightHeld by rightIS.collectIsPressedAsState()
    val jumpHeld by jumpIS.collectIsPressedAsState()

    // Camera center follows player smoothly
    var cam by remember { mutableStateOf(Vec(pos.x, pos.y)) }

    // Fixed visual scale: tileSize in px (responsive)
    var tilePx by remember { mutableStateOf(48f) }

    // Game loop
    LaunchedEffect(levelIndex, paused) {
        var lastT = 0L
        while (isActive) {
            val frameT = withFrameNanos { it }
            if (lastT == 0L) { lastT = frameT; continue }
            val dt = ((frameT - lastT) / 1_000_000_000.0).toFloat()
            lastT = frameT
            if (paused) continue

            // Horizontal control
            val targetAccel = when {
                leftHeld && !rightHeld -> -1f
                rightHeld && !leftHeld -> +1f
                else -> 0f
            }
            val accel = if (onGround) MOVE_ACCEL else AIR_ACCEL
            vel.x += targetAccel * accel * dt

            // Friction on ground when no input
            if (onGround && targetAccel == 0f) {
                val sign = if (vel.x > 0) 1 else -1
                val mag = (vel.x.absoluteValue - FRICTION * dt).coerceAtLeast(0f)
                vel.x = mag * sign
            }

            // Gravity
            vel.y += GRAVITY * dt
            if (vel.y > MAX_FALL) vel.y = MAX_FALL

            // Jump (edge-trigger while on ground)
            if (jumpHeld && onGround) {
                vel.y = -JUMP_VELOCITY
                onGround = false
            }

            // Integrate & collide (tile-by-tile)
            var newX = pos.x + vel.x * dt
            var newY = pos.y + vel.y * dt

            // X sweep
            val collidedX = collideBallWithTiles(
                level, newX, pos.y, BALL_RADIUS
            )
            if (collidedX) {
                // push out and bounce
                if (vel.x > 0) {
                    newX = floor(pos.x + BALL_RADIUS) + (1f - BALL_RADIUS) - 1e-3f
                } else if (vel.x < 0) {
                    newX = ceil(pos.x - BALL_RADIUS) + BALL_RADIUS + 1e-3f
                }
                vel.x = -vel.x * BOUNCE
            }

            // Y sweep
            val collidedY = collideBallWithTiles(
                level, newX, newY, BALL_RADIUS
            )
            if (collidedY) {
                if (vel.y > 0) {
                    newY = floor(pos.y + BALL_RADIUS) + (1f - BALL_RADIUS) - 1e-3f
                    vel.y = 0f
                    onGround = true
                } else if (vel.y < 0) {
                    newY = ceil(pos.y - BALL_RADIUS) + BALL_RADIUS + 1e-3f
                    vel.y = -vel.y * BOUNCE
                }
            } else {
                onGround = false
            }

            pos = Vec(newX, newY)

            // Hazard check (spikes)
            if (intersectsSpike(level, pos, BALL_RADIUS)) {
                lives -= 1
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (lives <= 0) {
                    // soft game over: reset lives & level
                    lives = 3
                    levelIndex = 0
                    level = Levels.load(levelIndex)
                }
                // respawn
                pos = Vec(level.spawn.x, level.spawn.y)
                vel = Vec(0f, 0f)
                onGround = false
            }

            // Exit check
            if (intersectsExit(level, pos, BALL_RADIUS)) {
                levelIndex = (levelIndex + 1) % Levels.count
                level = Levels.load(levelIndex)
                pos = Vec(level.spawn.x, level.spawn.y)
                vel = Vec(0f, 0f)
                onGround = false
            }

            // Camera ease towards player
            cam.x += (pos.x - cam.x) * 0.12f
            cam.y += (pos.y - cam.y) * 0.10f
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E13))) {
        // Game View
        Canvas(
            modifier = Modifier.fillMaxSize().align(Alignment.Center)
        ) {
            // compute tile size so that ~12–14 tiles fit height
            tilePx = size.height / 13f
            drawWorld(level, pos, cam, tilePx)
        }

        // HUD
        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0x88000000))
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Level ${'$'}{levelIndex + 1}", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text("Lives: ${'$'}lives", color = Color.White)
            }
        }

        // Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton("◀", leftIS)
            ControlButton("⏫", jumpIS)
            ControlButton("▶", rightIS)
        }

        // Pause/Restart buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { paused = !paused }) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = {
                lives = 3
                val lv = Levels.load(levelIndex)
                pos = Vec(lv.spawn.x, lv.spawn.y)
                vel = Vec(0f, 0f)
                onGround = false
            }) {
                Icon(Icons.Default.Replay, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun ControlButton(label: String, isource: MutableInteractionSource) {
    val pressed by isource.collectIsPressedAsState()
    val bg = if (pressed) Color(0xFF2B2B35) else Color(0xFF1A1A22)
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = isource, indication = null) { /* hold-only */ },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

// ===== Drawing =====
private fun DrawScope.drawWorld(level: Level, player: Vec, cam: Vec, tilePx: Float) {
    // world -> screen transform (center cam)
    val halfW = size.width / (2f * tilePx)
    val halfH = size.height / (2f * tilePx)

    fun worldToScreen(wx: Float, wy: Float): Offset {
        val sx = (wx - cam.x + halfW) * tilePx
        val sy = (wy - cam.y + halfH) * tilePx
        return Offset(sx, sy)
    }

    // sky gradient-ish
    drawRect(Color(0xFF0E0E13))

    // Visible tile range
    val minX = floor(cam.x - halfW - 2).toInt()
    val maxX = ceil(cam.x + halfW + 2).toInt()
    val minY = floor(cam.y - halfH - 2).toInt()
    val maxY = ceil(cam.y + halfH + 2).toInt()

    // Tiles
    for (ty in minY..maxY) {
        for (tx in minX..maxX) {
            val t = level.get(tx, ty)
            if (t == T_EMPTY) continue
            val topLeft = worldToScreen(tx.toFloat(), ty.toFloat())
            val rect = Rect(topLeft.x, topLeft.y, topLeft.x + tilePx, topLeft.y + tilePx)
            when (t) {
                T_SOLID -> {
                    drawRect(Color(0xFF2A2F3A), rect)
                    // edge highlight
                    drawRect(Color(0x33222A33), Rect(rect.left, rect.top, rect.right, rect.top + 4f))
                }
                T_SPIKE -> {
                    val p = Path().apply {
                        moveTo(rect.left, rect.bottom)
                        lineTo(rect.center.x, rect.top)
                        lineTo(rect.right, rect.bottom)
                        close()
                    }
                    drawPath(p, Color(0xFF8C2F39))
                }
                T_EXIT -> {
                    drawOval(Color(0xFF3AD1C5), rect)
                    drawOval(Color(0x6600FFFF), Rect(rect.left+6, rect.top+6, rect.right-6, rect.bottom-6))
                }
                T_SPAWN -> {
                    drawRect(Color(0xFF334455), rect)
                }
            }
        }
    }

    // Player ball
    val ballCenter = worldToScreen(player.x, player.y)
    drawCircle(Color(0xFFFEB019), radius = BALL_RADIUS * tilePx, center = ballCenter)
    // simple specular
    drawCircle(Color(0x22FFFFFF), radius = BALL_RADIUS * tilePx * 0.6f, center = ballCenter - Offset(BALL_RADIUS*tilePx*0.3f, BALL_RADIUS*tilePx*0.35f))
}

// ===== Collision & queries =====
private fun Level.get(x: Int, y: Int): Char {
    if (x < 0 || y < 0 || x >= w || y >= h) return T_SOLID // out-of-bounds as solid
    return tiles[y][x]
}

private fun collideBallWithTiles(level: Level, px: Float, py: Float, r: Float): Boolean {
    val minX = floor(px - r).toInt()
    val maxX = floor(px + r).toInt()
    val minY = floor(py - r).toInt()
    val maxY = floor(py + r).toInt()
    for (ty in minY..maxY) {
        for (tx in minX..maxX) {
            if (level.get(tx, ty) == T_SOLID) {
                // AABB of tile
                val nearestX = clamp(px, tx.toFloat(), tx + 1f)
                val nearestY = clamp(py, ty.toFloat(), ty + 1f)
                val dx = px - nearestX
                val dy = py - nearestY
                if (dx*dx + dy*dy <= r*r) return true
            }
        }
    }
    return false
}

private fun intersectsSpike(level: Level, p: Vec, r: Float): Boolean {
    val minX = floor(p.x - r).toInt()
    val maxX = floor(p.x + r).toInt()
    val minY = floor(p.y - r).toInt()
    val maxY = floor(p.y + r).toInt()
    for (ty in minY..maxY) {
        for (tx in minX..maxX) {
            if (level.get(tx, ty) == T_SPIKE) {
                // triangle hit (bottom base, peak up)
                val localX = p.x - tx
                val localY = p.y - ty
                if (localY >= 0f && localY <= 1f && localX >= 0f && localX <= 1f) {
                    val peakY = 1f - localX.absoluteValue * 2f
                    if (localY <= (1f - localX)) {
                        // approx: if ball center inside triangle with some radius bias
                        val distToTip = (localX - 0.5f)*(localX - 0.5f) + (localY - 0f)*(localY - 0f)
                        if (distToTip <= (r*r) || localY < (0.2f + r*0.6f)) return true
                    }
                }
            }
        }
    }
    return false
}

private fun intersectsExit(level: Level, p: Vec, r: Float): Boolean {
    val minX = floor(p.x - r).toInt()
    val maxX = floor(p.x + r).toInt()
    val minY = floor(p.y - r).toInt()
    val maxY = floor(p.y + r).toInt()
    for (ty in minY..maxY) {
        for (tx in minX..maxX) {
            if (level.get(tx, ty) == T_EXIT) {
                val dx = p.x - (tx + 0.5f)
                val dy = p.y - (ty + 0.5f)
                if (dx*dx + dy*dy <= (0.6f + r)*(0.6f + r)) return true
            }
        }
    }
    return false
}
