package com.cardvault.app.ui.effects

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * 设备倾斜状态（x/y ∈ -1..1，已低通平滑）。
 * 在 Activity 根部注册一次传感器监听，通过 CompositionLocal 下发，
 * 避免列表中每张卡各自注册监听。
 */
val LocalDeviceTilt = staticCompositionLocalOf<State<Offset>> {
    mutableStateOf(Offset.Zero)
}

@Composable
fun rememberDeviceTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            // 基线 = 缓慢跟随的“当前握持姿态”。高光响应的是相对基线的变化量，
            // 而不是绝对重力方向——否则竖持手机时 y 分量已接近满量程被钳位，
            // 前后倾斜没有任何输出（此前的 bug）。静止几秒后基线追上来，高光自动回中。
            private var baseX = 0f
            private var baseY = 0f
            private var initialized = false

            override fun onSensorChanged(event: SensorEvent) {
                val gx = (-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val gy = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                if (!initialized) {
                    baseX = gx
                    baseY = gy
                    initialized = true
                }
                baseX += (gx - baseX) * 0.008f
                baseY += (gy - baseY) * 0.008f
                val dx = ((gx - baseX) * 2.6f).coerceIn(-1f, 1f)
                val dy = ((gy - baseY) * 2.6f).coerceIn(-1f, 1f)
                val prev = tilt.value
                // 低通平滑，去抖动同时保持跟手
                val nx = prev.x + (dx - prev.x) * 0.16f
                val ny = prev.y + (dy - prev.y) * 0.16f
                // 变化阈值门控：手机静置时传感器噪声不再触发状态更新，
                // 否则列表里每张可见卡都会被这 50Hz 的噪声拖着持续重绘
                if (abs(nx - prev.x) > 0.002f || abs(ny - prev.y) > 0.002f) {
                    tilt.value = Offset(nx, ny)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}
