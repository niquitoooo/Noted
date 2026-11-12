package com.jot.app.ui

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Detecta si hay un teclado físico conectado (por ejemplo, el cover con teclado
 * de Samsung) para no mostrar el teclado táctil encima.
 *
 * Se actualiza cuando se conecta o desconecta un dispositivo de entrada.
 */
@Composable
fun rememberPhysicalKeyboard(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    }

    fun detect(): Boolean {
        val inputManager = manager ?: return false
        return inputManager.inputDeviceIds.any { id ->
            val device = inputManager.getInputDevice(id) ?: return@any false
            val external = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                device.isExternal
            } else {
                val name = device.name.lowercase()
                name.contains("keyboard") || name.contains("keypad") || name.contains("book cover")
            }
            !device.isVirtual &&
                external &&
                (device.sources and InputDevice.SOURCE_KEYBOARD) != 0
        }
    }

    var present by remember { mutableStateOf(detect()) }

    DisposableEffect(manager) {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                present = detect()
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                present = detect()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                present = detect()
            }
        }
        manager?.registerInputDeviceListener(listener, null)
        onDispose { manager?.unregisterInputDeviceListener(listener) }
    }

    return present
}
