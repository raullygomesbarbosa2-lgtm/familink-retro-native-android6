package com.familink.emulator

import android.content.Context
import android.view.View
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

class MainActivity : ComponentActivity() {
    private var surfaceRef: EmulatorSurfaceView? = null
    private var btMask = 0
    private var touchMask = 0
    private val btNamesState = mutableStateOf<List<String>>(emptyList())

    private fun refreshBluetoothPads() {
        val im = getSystemService(Context.INPUT_SERVICE) as InputManager
        btNamesState.value = im.inputDeviceIds.toList().mapNotNull { id ->
            val dev = InputDevice.getDevice(id) ?: return@mapNotNull null
            val isPad = (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (isPad) dev.name else null
        }
    }

    private fun updateTouchBit(bit: Int, down: Boolean) {
        touchMask = if (down) (touchMask or (1 shl bit)) else (touchMask and (1 shl bit).inv())
        surfaceRef?.p1Mask = btMask or touchMask
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        refreshBluetoothPads()

        setContent {
            val ctx = LocalContext.current
            var loadedRom by remember { mutableStateOf<VerifiedRom?>(null) }
            var targetFps by remember { mutableStateOf(30) }
            var statusMsg by remember { mutableStateOf("Sem jogos de exemplo · Selecione sua ROM") }
            val btDevices by btNamesState

            val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    Thread {
                        try {
                            val rom = RomVerifier.loadFromUri(ctx, uri)
                            val coreFile = File(ctx.filesDir, "cores/${rom.coreSpec.nativeSoName}")
                            if (!coreFile.exists()) {
                                coreFile.parentFile?.mkdirs()
                                val nativeAbi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" } ?: "arm64-v8a"
                                val coreUrl = rom.coreSpec.buildbotZipUrl.replace("/arm64-v8a/", "/$nativeAbi/")
                                val connection = (URL(coreUrl).openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 20000
                                    readTimeout = 60000
                                }
                                connection.inputStream.use { input ->
                                    ZipInputStream(input).use { zip ->
                                        var found = false
                                        var entry = zip.nextEntry
                                        while (entry != null) {
                                            if (!entry.isDirectory && entry.name.endsWith(".so")) {
                                                coreFile.outputStream().use { output -> zip.copyTo(output) }
                                                found = true
                                                break
                                            }
                                            entry = zip.nextEntry
                                        }
                                        if (!found) throw IllegalStateException("O pacote do núcleo veio sem arquivo .so.")
                                    }
                                }
                                connection.disconnect()
                            }
                            NativeCoreBridge.nativeUnload()
                            if (!NativeCoreBridge.nativeLoadCore(coreFile.absolutePath)) throw IllegalStateException("Falha ao carregar o núcleo nativo ${rom.coreSpec.nativeSoName}.")
                            if (!NativeCoreBridge.nativeLoadRomBytes(rom.fileName, rom.bytes)) throw IllegalStateException("O núcleo não aceitou esta ROM.")
                            runOnUiThread {
                                loadedRom = rom
                                statusMsg = "${rom.coreSpec.consoleName} · ${rom.region} · SHA256 ${rom.sha256.take(10)}"
                            }
                        } catch (err: Exception) {
                            runOnUiThread { statusMsg = err.message ?: "Erro ao carregar ROM" }
                        }
                    }.start()
                }
            }

            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("FamiLink Nativo (Android SDK)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(statusMsg, color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (btDevices.isEmpty()) "Bluetooth: Aguardando controle (auto)"
                            else "Bluetooth ativo: ${btDevices.joinToString()}",
                            color = if (btDevices.isEmpty()) Color(0xFF38BDF8) else Color(0xFF34D399),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { targetFps = if (targetFps == 30) 60 else 30 }) {
                            Text(if (targetFps == 30) "30 FPS" else "60 FPS", color = Color.White)
                        }
                        Button(onClick = { romPicker.launch(arrayOf("*/*")) }) {
                            Text("Abrir ROM")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                    if (loadedRom != null) {
                        AndroidView(
                            factory = { context ->
                                EmulatorSurfaceView(context).also {
                                    it.targetFps = targetFps
                                    surfaceRef = it
                                }
                            },
                            update = { view -> view.targetFps = targetFps },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Controle PlayStation na tela ativo automaticamente logo ao abrir
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF12161F)).padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // D-Pad
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PsPadButton("▲", 4) { bit, down -> updateTouchBit(bit, down) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PsPadButton("◀", 6) { bit, down -> updateTouchBit(bit, down) }
                            PsPadButton("▶", 7) { bit, down -> updateTouchBit(bit, down) }
                        }
                        PsPadButton("▼", 5) { bit, down -> updateTouchBit(bit, down) }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PsPadButton("SEL", 2) { bit, down -> updateTouchBit(bit, down) }
                        PsPadButton("STR", 3) { bit, down -> updateTouchBit(bit, down) }
                    }

                    // Botões PlayStation (△ ○ ✕ □)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PsPadButton("△", 8, Color(0xFF10B981)) { bit, down -> updateTouchBit(bit, down) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PsPadButton("□", 9, Color(0xFFEC4899)) { bit, down -> updateTouchBit(bit, down) }
                            PsPadButton("○", 0, Color(0xFFEF4444)) { bit, down -> updateTouchBit(bit, down) }
                        }
                        PsPadButton("✕", 1, Color(0xFF3B82F6)) { bit, down -> updateTouchBit(bit, down) }
                    }
                }
            }
        }
    }

    @Composable
    private fun PsPadButton(
        label: String,
        bit: Int,
        tint: Color = Color.White,
        onTouch: (Int, Boolean) -> Unit
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF1D2332), CircleShape)
                .pointerInput(bit) {
                    detectTapGestures(
                        onPress = {
                            onTouch(bit, true)
                            tryAwaitRelease()
                            onTouch(bit, false)
                        }
                    )
                }
        ) {
            Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var next = btMask and (0xF0.inv())
            if (y < -0.4f || hatY < -0.5f) next = next or (1 shl 4)
            if (y > 0.4f || hatY > 0.5f) next = next or (1 shl 5)
            if (x < -0.4f || hatX < -0.5f) next = next or (1 shl 6)
            if (x > 0.4f || hatX > 0.5f) next = next or (1 shl 7)
            btMask = next
            surfaceRef?.p1Mask = btMask or touchMask
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        refreshBluetoothPads()
        val bit = mapKeyBit(keyCode)
        if (bit >= 0) {
            btMask = btMask or (1 shl bit)
            surfaceRef?.p1Mask = btMask or touchMask
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val bit = mapKeyBit(keyCode)
        if (bit >= 0) {
            btMask = btMask and (1 shl bit).inv()
            surfaceRef?.p1Mask = btMask or touchMask
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun mapKeyBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X -> 0   // ○ / A
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_Z -> 1   // ✕ / B
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_LEFT -> 2
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> 3
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> 4
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> 5
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> 6
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> 7
        KeyEvent.KEYCODE_BUTTON_Y -> 8                       // △ / X
        KeyEvent.KEYCODE_BUTTON_X -> 9                       // □ / Y
        KeyEvent.KEYCODE_BUTTON_L1 -> 10
        KeyEvent.KEYCODE_BUTTON_R1 -> 11
        KeyEvent.KEYCODE_BUTTON_L2 -> 12
        KeyEvent.KEYCODE_BUTTON_R2 -> 13
        else -> -1
    }
}
