package com.familink.emulator

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private val Bg = Color(0xFF0B0E14)
private val Panel = Color(0xFF12161F)
private val Accent = Color(0xFFE11D48)
private val Muted = Color(0xFF9CA3AF)

class MainActivity : ComponentActivity() {
    private var surfaceRef: EmulatorSurfaceView? = null
    private var btMask = 0
    private var touchMask = 0
    private var analogMask = 0
    private var localP2 = false
    private val btNamesState = mutableStateOf<List<String>>(emptyList())

    private fun refreshBluetoothPads() {
        val im = getSystemService(Context.INPUT_SERVICE) as InputManager
        btNamesState.value = im.inputDeviceIds.toList().mapNotNull { id ->
            val dev = InputDevice.getDevice(id) ?: return@mapNotNull null
            val pad = (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (pad) dev.name else null
        }
    }

    private fun updateTouchBit(bit: Int, down: Boolean) {
        touchMask = if (down) (touchMask or (1 shl bit)) else (touchMask and (1 shl bit).inv())
        if (localP2) surfaceRef?.p2Mask = touchMask or analogMask else surfaceRef?.p1Mask = btMask or touchMask or analogMask
    }

    private fun updateAnalog(x: Float, y: Float) {
        val v = if (kotlin.math.abs(x) < 0.18f && kotlin.math.abs(y) < 0.18f) 0 else 1
        analogMask = if (v == 0) 0 else {
            var mask = 0
            if (y < -0.45f) mask = mask or (1 shl 4)
            if (y > 0.45f) mask = mask or (1 shl 5)
            if (x < -0.45f) mask = mask or (1 shl 6)
            if (x > 0.45f) mask = mask or (1 shl 7)
            mask
        }
        if (localP2) {
            surfaceRef?.p2AnalogX = x; surfaceRef?.p2AnalogY = y
            surfaceRef?.p2Mask = touchMask or analogMask
        } else {
            surfaceRef?.p1AnalogX = x; surfaceRef?.p1AnalogY = y
            surfaceRef?.p1Mask = btMask or touchMask or analogMask
        }
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        refreshBluetoothPads()
        NativeFirebase.initialize(this)
        setContent { FamiLinkApp() }
    }

    @Composable
    private fun FamiLinkApp() {
        val ctx = LocalContext.current
        val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val auth = remember { FirebaseAuth.getInstance() }
        val db = remember { FirebaseFirestore.getInstance(NativeFirebase.initialize(ctx), NativeFirebase.databaseId()) }
        var user by remember { mutableStateOf(auth.currentUser) }
        var authDialog by remember { mutableStateOf(false) }
        var authMode by remember { mutableStateOf("login") }
        var tab by remember { mutableStateOf("Jogar") }
        var rom by remember { mutableStateOf<VerifiedRom?>(null) }
        var status by remember { mutableStateOf("Carregue uma ROM sua para jogar") }
        var targetFps by remember { mutableIntStateOf(60) }
        var localTwoPlayer by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var selectedCoreId by remember { mutableStateOf<String?>(null) }
        var roomCode by remember { mutableStateOf("") }
        var roomInfo by remember { mutableStateOf<Map<String, Any>?>(null) }
        var roomError by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val btDevices by btNamesState

        DisposableEffect(auth) {
            val listener = FirebaseAuth.AuthStateListener { instance -> user = instance.currentUser }
            auth.addAuthStateListener(listener)
            onDispose { auth.removeAuthStateListener(listener) }
        }
        DisposableEffect(roomCode, user?.uid) {
            if (roomCode.isBlank() || user == null) { roomInfo = null; onDispose { } }
            else {
                val reg = db.collection("rooms").document(roomCode).addSnapshotListener { snap, err ->
                    if (err != null) roomError = err.localizedMessage ?: "Falha ao atualizar sala"
                    else roomInfo = if (snap != null && snap.exists()) snap.data else null
                }
                onDispose { reg.remove() }
            }
        }

        val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                busy = true
                status = "Lendo ROM e preparando o núcleo nativo…"
                Thread {
                    try {
                        val loaded = RomVerifier.loadFromUri(ctx, uri, selectedCoreId)
                        val coreFile = File(ctx.filesDir, "cores/${loaded.coreSpec.nativeSoName}")
                        if (!coreFile.exists()) {
                            coreFile.parentFile?.mkdirs()
                            val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" } ?: "arm64-v8a"
                            val url = loaded.coreSpec.buildbotZipUrl.replace("/arm64-v8a/", "/$abi/")
                            val connection = URL(url).openConnection() as HttpURLConnection
                            connection.connectTimeout = 20000; connection.readTimeout = 60000
                            connection.inputStream.use { input -> ZipInputStream(input).use { zip ->
                                var entry = zip.nextEntry; var found = false
                                while (entry != null) {
                                    if (!entry.isDirectory && entry.name.endsWith(".so")) {
                                        coreFile.outputStream().use { output -> zip.copyTo(output) }; found = true; break
                                    }
                                    entry = zip.nextEntry
                                }
                                if (!found) error("Pacote do núcleo não contém uma biblioteca .so")
                            } }
                            connection.disconnect()
                        }
                        NativeCoreBridge.nativeUnload()
                        check(NativeCoreBridge.nativeLoadCore(coreFile.absolutePath)) { "Não consegui abrir o núcleo ${loaded.coreSpec.nativeSoName}" }
                        check(NativeCoreBridge.nativeLoadRomBytes(loaded.fileName, loaded.bytes)) { "O núcleo não aceitou essa ROM" }
                        runOnUiThread { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; rom = loaded; status = "${loaded.coreSpec.consoleName} · ${loaded.region} · ${loaded.fileName}"; busy = false }
                    } catch (e: Exception) {
                        runOnUiThread { status = e.message ?: "Erro ao carregar ROM"; busy = false }
                    }
                }.start()
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
            if (rom != null && isLandscape) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { c -> EmulatorSurfaceView(c).also { it.targetFps = targetFps; surfaceRef = it } }, update = { it.targetFps = targetFps }, modifier = Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.TopStart).padding(start=8.dp,top=4.dp), verticalAlignment=Alignment.CenterVertically) {
                        Text(status, color=Color.White.copy(alpha=0.82f), fontSize=10.sp, modifier=Modifier.background(Color.Black.copy(alpha=0.28f),RoundedCornerShape(6.dp)).padding(horizontal=6.dp,vertical=3.dp), maxLines=1)
                        TextButton(onClick={ requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }, modifier=Modifier.padding(start=4.dp), colors=ButtonDefaults.textButtonColors(containerColor=Color.Black.copy(alpha=0.22f))) { Text("Voltar",color=Color.White,fontSize=10.sp) }
                    }
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.Bottom) {
                        AnalogStick(onMove={x,y->updateAnalog(x,y)})
                        Row(horizontalArrangement=Arrangement.spacedBy(5.dp), verticalAlignment=Alignment.CenterVertically) { PadButton("SEL",2,buttonSize=54.dp){b,d->updateTouchBit(b,d)}; PadButton("STR",3,buttonSize=54.dp){b,d->updateTouchBit(b,d)} }
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) { PadButton("L1",10,buttonSize=54.dp){b,d->updateTouchBit(b,d)}; PadButton("R1",11,buttonSize=54.dp){b,d->updateTouchBit(b,d)} }
                            PadButton("△",8,Color(0xFF10B981),buttonSize=64.dp){b,d->updateTouchBit(b,d)}
                            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) { PadButton("□",9,Color(0xFFEC4899),buttonSize=64.dp){b,d->updateTouchBit(b,d)}; PadButton("○",0,Color(0xFFEF4444),buttonSize=64.dp){b,d->updateTouchBit(b,d)} }
                            PadButton("✕",1,Color(0xFF3B82F6),buttonSize=64.dp){b,d->updateTouchBit(b,d)}
                        }
                    }
                }
            } else Column(Modifier.fillMaxSize().background(Bg)) {
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FamiLink", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("Android nativo · SDK", color = Muted, fontSize = 11.sp)
                    }
                    if (user == null) {
                        Button(onClick = { authMode = "login"; authDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Entrar com e-mail", fontSize = 12.sp) }
                    } else {
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable { auth.signOut(); roomCode = "" }) {
                            Text(user?.email ?: "Conta", color = Color.White, fontSize = 11.sp, maxLines = 1)
                            Text("Toque para sair", color = Muted, fontSize = 9.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 7.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Jogar", "Online", "Local", "Núcleos").forEach { label ->
                        TextButton(onClick = { tab = label }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(containerColor = if (tab == label) Accent else Panel)) {
                            Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                when (tab) {
                    "Jogar" -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(status, color = Muted, fontSize = 11.sp, maxLines = 2)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { romPicker.launch(arrayOf("*/*")) }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (busy) "Carregando…" else "Abrir ROM") }
                            OutlinedButton(onClick = { targetFps = if (targetFps == 30) 60 else 30 }) { Text("$targetFps FPS", color = Color.White) }
                            TextButton(onClick = { localTwoPlayer = !localTwoPlayer; localP2 = localTwoPlayer; touchMask = 0; analogMask = 0; surfaceRef?.p1AnalogX = 0f; surfaceRef?.p1AnalogY = 0f; surfaceRef?.p2AnalogX = 0f; surfaceRef?.p2AnalogY = 0f }) { Text(if (localTwoPlayer) "2 jogadores" else "Solo", color = if (localTwoPlayer) Color(0xFF34D399) else Color.White, fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            if (rom == null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("FamiLink", color = Color(0xFF2A3140), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp)); Text("Escolha uma ROM para começar", color = Muted, fontSize = 12.sp)
                                }
                            } else AndroidView(factory = { c -> EmulatorSurfaceView(c).also { it.targetFps = targetFps; surfaceRef = it } }, update = { it.targetFps = targetFps }, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(if (btDevices.isEmpty()) "Controle Bluetooth/USB: aguardando" else "Controle conectado: ${btDevices.joinToString()}", color = if (btDevices.isEmpty()) Muted else Color(0xFF34D399), fontSize = 10.sp)
                        Spacer(Modifier.height(5.dp))
                        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            AnalogStick(onMove = { x, y -> updateAnalog(x, y) })
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PadButton("SEL",2,buttonSize=54.dp){b,d->updateTouchBit(b,d)}; PadButton("STR",3,buttonSize=54.dp){b,d->updateTouchBit(b,d)} }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PadButton("△",8,Color(0xFF10B981),buttonSize=64.dp){b,d->updateTouchBit(b,d)}
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PadButton("□",9,Color(0xFFEC4899),buttonSize=64.dp){b,d->updateTouchBit(b,d)}; PadButton("○",0,Color(0xFFEF4444),buttonSize=64.dp){b,d->updateTouchBit(b,d)} }
                                PadButton("✕",1,Color(0xFF3B82F6),buttonSize=64.dp){b,d->updateTouchBit(b,d)}
                            }
                        }
                    }
                    "Núcleos" -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text("Núcleos & ROMs · ${CoreRegistry.cores.size} sistemas", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Toque num sistema e escolha ‘Abrir ROM’ para usar esse núcleo.", color = Muted, fontSize = 11.sp)
                        selectedCoreId?.let { id ->
                            val selected = CoreRegistry.cores.firstOrNull { it.id == id }
                            if (selected != null) Text("Selecionado: ${selected.consoleName}", color = Color(0xFF34D399), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Buscar console ou extensão") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(CoreRegistry.cores.filter { it.consoleName.contains(search, true) || it.id.contains(search, true) || it.extensions.any { e -> e.contains(search, true) } }) { core ->
                                val isSelected = selectedCoreId == core.id
                                Column(Modifier.fillMaxWidth().background(if (isSelected) Color(0xFF193329) else Panel, RoundedCornerShape(10.dp)).clickable { selectedCoreId = core.id }.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(core.consoleName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text("ROM: ${core.extensions.joinToString()} · Núcleo: ${core.nativeSoName}", color = Muted, fontSize = 10.sp)
                                        }
                                        Button(onClick = {
                                            selectedCoreId = core.id
                                            tab = "Jogar"
                                            romPicker.launch(arrayOf("*/*"))
                                        }, colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color(0xFF059669) else Accent), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                                            Text(if (isSelected) "Abrir ROM" else "Usar", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "Online", "Local" -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(if (tab == "Online") "Netplay Online" else "Netplay Local", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(if (tab == "Online") "Entre com sua conta para criar ou localizar uma sala." else "Modo local para dois jogadores no mesmo aparelho.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        if (tab == "Local") {
                            Button(onClick = { tab = "Jogar"; localTwoPlayer = true; localP2 = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Abrir jogo em modo local 2P") }
                            Text("Carregue a ROM em Jogar e ative 2 jogadores para usar os controles locais.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top=8.dp))
                        } else if (user == null) {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp)) { Text("Conta necessária", color = Color.White, fontWeight = FontWeight.Bold); Text("Crie sua conta ou entre com e-mail e senha. Você também pode recuperar a senha por e-mail.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical=8.dp)); Button(onClick = { authMode="login"; authDialog=true }, colors=ButtonDefaults.buttonColors(containerColor=Accent)) { Text("Entrar / criar conta") } } }
                        } else {
                            Text("Conectado como ${user?.email}", color = Color(0xFF34D399), fontSize = 11.sp)
                            Text("ROM atual: ${rom?.fileName ?: "nenhuma carregada"}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top=8.dp))
                            Spacer(Modifier.height(12.dp))
                            Button(enabled = !busy && rom != null, onClick = {
                                busy = true; roomError = ""
                                Thread {
                                    val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
                                    val active = rom
                                    if (active == null) { runOnUiThread { busy=false; roomError="Carregue uma ROM antes de criar a sala." }; return@Thread }
                                    val payload = hashMapOf<String, Any>("roomCode" to code, "hostUid" to (user?.uid ?: ""), "hostName" to (user?.email?.substringBefore("@") ?: "Host P1"), "guestUid" to "", "guestName" to "", "mode" to "remote", "consoleId" to active.coreSpec.id, "romName" to active.fileName, "romHash" to active.sha256, "romRegion" to active.region.name, "romSize" to active.bytes.size, "guestRomHash" to "", "guestRomRegion" to "NONE", "guestAccessStatus" to "empty", "status" to "waiting", "inputDelay" to 1, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp())
                                    db.collection("rooms").document(code).set(payload).addOnSuccessListener { roomCode=code;busy=false }.addOnFailureListener { e->roomError=e.localizedMessage ?: "Erro ao criar sala";busy=false }
                                }.start()
                            }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (busy) "Aguarde…" else "Criar sala") }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(roomCode, { roomCode=it.uppercase().take(6) }, label={Text("Código da sala")}, singleLine=true)
                            Button(enabled = !busy && roomCode.length == 6 && rom != null, onClick = {
                                val code = roomCode.trim(); val active = rom ?: return@Button; busy=true; roomError=""
                                db.collection("rooms").document(code).get().addOnSuccessListener { doc ->
                                    if (!doc.exists()) { roomError="Sala não encontrada."; busy=false }
                                    else {
                                        val hash=doc.getString("romHash") ?: ""; val region=doc.getString("romRegion") ?: ""
                                        if (hash != active.sha256 || region != active.region.name) { roomError="A ROM precisa ser idêntica (SHA-256) e da mesma região da sala."; busy=false }
                                        else doc.reference.update(mapOf("guestUid" to (user?.uid ?: ""), "guestName" to (user?.email?.substringBefore("@") ?: "P2"), "guestRomHash" to active.sha256, "guestRomRegion" to active.region.name, "guestAccessStatus" to "pending", "updatedAt" to FieldValue.serverTimestamp())).addOnSuccessListener { busy=false }.addOnFailureListener { e->roomError=e.localizedMessage ?: "Falha ao entrar";busy=false }
                                    }
                                }.addOnFailureListener { e->roomError=e.localizedMessage ?: "Falha ao procurar sala";busy=false }
                            }) { Text("Entrar na sala") }
                            if (roomCode.isNotBlank() && roomInfo != null) {
                                Spacer(Modifier.height(12.dp)); Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(14.dp)) {
                                    Text("Sala $roomCode · ${roomInfo?.get("status") ?: ""}", color=Color.White, fontWeight=FontWeight.Bold)
                                    Text("ROM: ${roomInfo?.get("romName") ?: ""} · ${roomInfo?.get("romRegion") ?: ""}", color=Muted, fontSize=11.sp)
                                    Text("P2: ${roomInfo?.get("guestName")?.takeIf { it.toString().isNotBlank() } ?: "aguardando"} · ${roomInfo?.get("guestAccessStatus") ?: ""}", color=Muted, fontSize=11.sp)
                                    if (roomInfo?.get("hostUid") == user?.uid && roomInfo?.get("guestUid").toString().isNotBlank()) {
                                        Button(onClick = { db.collection("rooms").document(roomCode).update(mapOf("guestAccessStatus" to "approved", "status" to "playing", "updatedAt" to FieldValue.serverTimestamp())) }, colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF059669))) { Text("Liberar P2") }
                                    }
                                    Text("A sala e a validação da ROM já usam sua conta; a sincronização ao vivo dos controles ainda precisa ser portada para esta versão nativa.", color=Color(0xFFFFC857), fontSize=10.sp, modifier=Modifier.padding(top=6.dp))
                                } }
                            }
                        }
                        if (roomError.isNotBlank()) Text(roomError, color=Color(0xFFF87171), fontSize=11.sp, modifier=Modifier.padding(top=8.dp))
                    }
                }
            }
        }
        if (authDialog) NativeAuthDialog(authMode, { authMode=it }, { authDialog=false })
    }

    @Composable
    private fun PadButton(label: String, bit: Int, tint: Color = Color.White, buttonSize: Dp = 60.dp, onTouch: (Int, Boolean) -> Unit) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(buttonSize).background(Color.Transparent, CircleShape).border(1.dp, tint.copy(alpha = 0.48f), CircleShape).pointerInput(bit) {
            detectTapGestures(onPress = { onTouch(bit,true); tryAwaitRelease(); onTouch(bit,false) })
        }) { Text(label, color=tint, fontSize=13.sp) }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            val x=event.getAxisValue(MotionEvent.AXIS_X); val y=event.getAxisValue(MotionEvent.AXIS_Y)
            val hx=event.getAxisValue(MotionEvent.AXIS_HAT_X); val hy=event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var next=btMask and 0xF0.inv()
            if(y < -0.4f || hy < -0.5f) next=next or (1 shl 4); if(y > 0.4f || hy > 0.5f) next=next or (1 shl 5)
            if(x < -0.4f || hx < -0.5f) next=next or (1 shl 6); if(x > 0.4f || hx > 0.5f) next=next or (1 shl 7)
            btMask=next; surfaceRef?.p1Mask=btMask or touchMask; return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        refreshBluetoothPads(); val bit=mapKeyBit(keyCode)
        if(bit>=0){btMask=btMask or (1 shl bit);surfaceRef?.p1Mask=btMask or touchMask;return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val bit=mapKeyBit(keyCode)
        if(bit>=0){btMask=btMask and (1 shl bit).inv();surfaceRef?.p1Mask=btMask or touchMask;return true}
        return super.onKeyUp(keyCode,event)
    }
    private fun mapKeyBit(keyCode:Int):Int=when(keyCode){
        KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_X->0; KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_Z->1
        KeyEvent.KEYCODE_BUTTON_SELECT,KeyEvent.KEYCODE_SHIFT_LEFT->2; KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_ENTER->3
        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_W->4; KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_S->5
        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_A->6; KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_D->7
        KeyEvent.KEYCODE_BUTTON_Y->8; KeyEvent.KEYCODE_BUTTON_X->9; KeyEvent.KEYCODE_BUTTON_L1->10; KeyEvent.KEYCODE_BUTTON_R1->11
        KeyEvent.KEYCODE_BUTTON_L2->12; KeyEvent.KEYCODE_BUTTON_R2->13; else->-1
    }
}

@Composable
private fun AnalogStick(onMove: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier = Modifier
            .size(154.dp)
            .pointerInput(Unit) {
                val radius = minOf(size.width.toFloat(), size.height.toFloat()) * 0.34f
                fun update(position: Offset) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    var dx = position.x - center.x
                    var dy = position.y - center.y
                    val length = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (length > radius) { dx = dx * radius / length; dy = dy * radius / length }
                    knob = Offset(dx, dy)
                    onMove((dx / radius).coerceIn(-1f, 1f), (dy / radius).coerceIn(-1f, 1f))
                }
                detectDragGestures(
                    onDragStart = { update(it) },
                    onDragEnd = { knob = Offset.Zero; onMove(0f, 0f) },
                    onDragCancel = { knob = Offset.Zero; onMove(0f, 0f) },
                    onDrag = { change, _ -> change.consumeAllChanges(); update(change.position) }
                )
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = minOf(size.width, size.height) * 0.45f
        drawCircle(color = Color.Transparent, radius = outer, center = center)
        drawCircle(color = Color(0xFF64748B).copy(alpha=0.72f), radius = outer, center = center, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = Color(0xFF39465C), radius = outer * 0.55f, center = center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(color = Color(0xFF0F172A), radius = outer * 0.36f, center = center + knob)
        drawCircle(color = Color(0xFF38BDF8), radius = outer * 0.32f, center = center + knob, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun NativeAuthDialog(modeInit: String, onMode: (String)->Unit, onClose: ()->Unit) {
    val auth= remember { FirebaseAuth.getInstance() }
    var mode by remember { mutableStateOf(modeInit) }
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }; var notice by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onClose, title={Text(when(mode){"register"->"Criar conta";"reset"->"Recuperar senha";else->"Entrar com e-mail"})},
        text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(email,{email=it},label={Text("E-mail")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
            if(mode!="reset") OutlinedTextField(password,{password=it},label={Text("Senha · mínimo 6 caracteres")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            if(mode=="register") OutlinedTextField(confirm,{confirm=it},label={Text("Confirmar senha")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            if(error.isNotBlank()) Text(error,color=Color(0xFFF87171),fontSize=11.sp)
            if(notice.isNotBlank()) Text(notice,color=Color(0xFF34D399),fontSize=11.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                if(mode!="login") TextButton(onClick={mode="login";onMode(mode);error="";notice=""}) { Text("Entrar") }
                if(mode!="register") TextButton(onClick={mode="register";onMode(mode);error="";notice=""}) { Text("Criar conta") }
                if(mode!="reset") TextButton(onClick={mode="reset";onMode(mode);error="";notice=""}) { Text("Esqueci a senha") }
            }
        } },
        confirmButton={ Button(enabled=!busy,onClick={
            error="";notice="";val clean=email.trim()
            if(!clean.contains("@")){error="Digite um e-mail válido.";return@Button}
            if(mode!="reset" && password.length<6){error="A senha precisa ter pelo menos 6 caracteres.";return@Button}
            if(mode=="register" && password!=confirm){error="As senhas não coincidem.";return@Button}
            busy=true
            when(mode){
                "register"->auth.createUserWithEmailAndPassword(clean,password).addOnSuccessListener{busy=false;onClose()}.addOnFailureListener{e->busy=false;error=authError(e)}
                "reset"->auth.sendPasswordResetEmail(clean).addOnSuccessListener{busy=false;notice="Se a conta existir, o link de redefinição foi enviado. Confira o spam."}.addOnFailureListener{e->busy=false;error=authError(e)}
                else->auth.signInWithEmailAndPassword(clean,password).addOnSuccessListener{busy=false;onClose()}.addOnFailureListener{e->busy=false;error=authError(e)}
            }
        }) { Text(if(busy) "Aguarde…" else when(mode){"register"->"Criar conta";"reset"->"Enviar link";else->"Entrar"}) } }, dismissButton={TextButton(onClick=onClose){Text("Fechar")}})
}

private fun authError(e: Exception): String {
    val m=e.message?.lowercase() ?: ""
    return when {
        "email" in m && "already" in m -> "Esse e-mail já tem conta. Entre ou recupere a senha."
        "password" in m && ("weak" in m || "6 characters" in m) -> "A senha precisa ter pelo menos 6 caracteres."
        "credential" in m || "password" in m || "user" in m -> "E-mail ou senha incorretos. Confira ou recupere a senha."
        "network" in m -> "Sem conexão. Confira a internet e tente de novo."
        "operation" in m && "allowed" in m -> "Cadastro por e-mail ainda não está habilitado no Firebase."
        else -> e.localizedMessage ?: "Não foi possível concluir."
    }
}
