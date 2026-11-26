package com.familink.emulator

import android.content.Context
import android.graphics.Bitmap
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Streams only emulator-rendered game frames/audio. It never captures the Android display or notifications. */
class GameStreamSession(
    context: Context,
    private val db: FirebaseFirestore,
    private val roomCode: String,
    private val isHost: Boolean,
    private val onPacket: (kind: Int, payload: ByteArray, sampleRate: Int) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        @Volatile private var initialized = false
        private const val MAX_PACKET = 12_000
        private fun initialize(context: Context) {
            if (!initialized) synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                    initialized = true
                }
            }
        }
    }
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var channel: DataChannel? = null
    private val frameCounter = AtomicInteger(0)
    private val assemblies = ConcurrentHashMap<String, Assembly>()
    private var lastFrameAt = 0L
    private var listener: ListenerRegistration? = null
    private val localCandidateField = if (isHost) "streamHostCandidates" else "streamGuestCandidates"
    private val remoteCandidateField = if (isHost) "streamGuestCandidates" else "streamHostCandidates"
    private var remoteDescriptionSet = false
    private var latestSignalData: Map<String, Any> = emptyMap()
    private var lastRemoteCandidateCount = 0

    private data class Assembly(val chunks: Array<ByteArray?>, val sampleRate: Int, var received: Int = 0)

    init {
        initialize(context)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { onState("Conexão P2P: ${state.name.lowercase()}") }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                val value = hashMapOf("sdpMid" to (candidate.sdpMid ?: "0"), "sdpMLineIndex" to candidate.sdpMLineIndex, "sdp" to candidate.sdp)
                db.collection("rooms").document(roomCode).update(localCandidateField, FieldValue.arrayUnion(value))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: DataChannel) { attachChannel(channel) }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { onState("Stream: ${newState.name.lowercase()}") }
        }) ?: error("WebRTC não conseguiu criar a conexão.")
        listener = db.collection("rooms").document(roomCode).addSnapshotListener { snap, error ->
            if (error != null) { onState("Sinalização: ${error.localizedMessage ?: "erro"}"); return@addSnapshotListener }
            val data = snap?.data ?: return@addSnapshotListener
            handleSignal(data)
        }
    }

    fun startHost() {
        if (!isHost) return
        channel = peer.createDataChannel("familink-game", DataChannel.Init()).also { attachChannel(it) }
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) { peer.setLocalDescription(simpleObserver("Oferta local"), desc); updateDescription("streamOffer", desc) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { onState("Falha ao criar oferta: $error") }
            override fun onSetFailure(error: String) { onState("Falha ao configurar oferta: $error") }
        }, MediaConstraints())
    }

    private fun handleSignal(data: Map<String, Any>) {
        latestSignalData = data
        if (isHost) {
            val answer = data["streamAnswer"] as? Map<*, *>
            if (!remoteDescriptionSet && !((answer?.get("sdp") as? String).isNullOrBlank())) setRemote(answer!!, SessionDescription.Type.ANSWER)
        } else {
            val offer = data["streamOffer"] as? Map<*, *>
            if (!remoteDescriptionSet && !((offer?.get("sdp") as? String).isNullOrBlank())) {
                setRemote(offer, SessionDescription.Type.OFFER) {
                    peer.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription) { peer.setLocalDescription(simpleObserver("Resposta local"), desc); updateDescription("streamAnswer", desc) }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String) { onState("Falha ao responder: $error") }
                        override fun onSetFailure(error: String) { onState("Falha ao configurar resposta: $error") }
                    }, MediaConstraints())
                }
            }
        }
        if (remoteDescriptionSet) processRemoteCandidates(data)
    }

    private fun setRemote(map: Map<*, *>, type: SessionDescription.Type, after: (() -> Unit)? = null) {
        val sdp = map["sdp"] as? String ?: return
        peer.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {}
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                processRemoteCandidates(latestSignalData)
                after?.invoke()
            }
            override fun onCreateFailure(error: String) { onState("Falha SDP: $error") }
            override fun onSetFailure(error: String) { onState("Falha ao receber SDP: $error") }
        }, SessionDescription(type, sdp))
    }

    private fun processRemoteCandidates(data: Map<String, Any>) {
        val candidates = data[remoteCandidateField] as? List<*> ?: emptyList<Any>()
        if (candidates.size <= lastRemoteCandidateCount) return
        candidates.drop(lastRemoteCandidateCount).forEach { raw ->
            val c = raw as? Map<*, *> ?: return@forEach
            val sdp = c["sdp"] as? String ?: return@forEach
            val mid = c["sdpMid"] as? String ?: "0"
            val line = (c["sdpMLineIndex"] as? Number)?.toInt() ?: 0
            peer.addIceCandidate(IceCandidate(mid, line, sdp))
        }
        lastRemoteCandidateCount = candidates.size
    }

    private fun updateDescription(field: String, desc: SessionDescription) {
        db.collection("rooms").document(roomCode).update(field, mapOf("type" to desc.type.canonicalForm(), "sdp" to desc.description))
            .addOnFailureListener { onState("Falha na sinalização Firebase: ${it.localizedMessage}") }
    }

    private fun simpleObserver(name: String) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) { onState("$name: $error") }
        override fun onSetFailure(error: String) { onState("$name: $error") }
    }

    private fun attachChannel(value: DataChannel) {
        channel = value
        value.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() { onState("Canal P2P: ${value.state().name.lowercase()}") }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) receive(buffer.data)
            }
        })
    }

    /** Encodes only a copy of the emulator's game frame (no OS UI, controls, or notifications). */
    fun publishFrame(bitmap: Bitmap) {
        if (!isHost || channel?.state() != DataChannel.State.OPEN) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 100) return // cap stream at 10 fps for older Android 6 devices
        lastFrameAt = now
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, minOf(bitmap.width, 400), minOf(bitmap.height, 300), false)
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 58, output)
            if (scaled !== bitmap) scaled.recycle()
            sendPacket(1, output.toByteArray(), 0)
        } catch (_: Exception) { }
    }

    /** The samples come directly from the emulator core's PCM queue, not the microphone or system audio. */
    fun publishAudio(samples: ShortArray, count: Int, sampleRate: Int) {
        if (!isHost || channel?.state() != DataChannel.State.OPEN || count <= 0) return
        val n = minOf(count, samples.size)
        val bytes = ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bytes.putShort(samples[i])
        sendPacket(2, bytes.array(), sampleRate)
    }

    private fun sendPacket(kind: Int, bytes: ByteArray, rate: Int) {
        val dc = channel ?: return
        if (dc.state() != DataChannel.State.OPEN || dc.bufferedAmount() > 1_500_000) return
        val id = frameCounter.incrementAndGet()
        val payloadSize = MAX_PACKET - 13
        val total = (bytes.size + payloadSize - 1) / payloadSize
        for (part in 0 until total) {
            val start = part * payloadSize
            val length = minOf(payloadSize, bytes.size - start)
            val packet = ByteBuffer.allocate(13 + length).order(java.nio.ByteOrder.BIG_ENDIAN)
            packet.put(kind.toByte()); packet.putInt(id); packet.putShort(total.toShort()); packet.putShort(part.toShort()); packet.putInt(rate); packet.put(bytes, start, length)
            if (!dc.send(DataChannel.Buffer(packet, true))) break
        }
    }

    private fun receive(buffer: ByteBuffer) {
        if (buffer.remaining() < 13) return
        val b = buffer.slice().order(java.nio.ByteOrder.BIG_ENDIAN)
        val kind = b.get().toInt() and 0xff; val id = b.int
        val total = b.short.toInt() and 0xffff; val part = b.short.toInt() and 0xffff; val rate = b.int
        if ((kind != 1 && kind != 2) || total !in 1..256 || part >= total) return
        val payload = ByteArray(b.remaining()); b.get(payload)
        val key = "$kind:$id"
        val assembly = assemblies.computeIfAbsent(key) { Assembly(arrayOfNulls(total), rate) }
        synchronized(assembly) {
            if (assembly.chunks[part] == null) { assembly.chunks[part] = payload; assembly.received++ }
            if (assembly.received == assembly.chunks.size) {
                val out = ByteArrayOutputStream()
                assembly.chunks.forEach { out.write(it ?: return) }
                assemblies.remove(key)
                onPacket(kind, out.toByteArray(), assembly.sampleRate)
            }
        }
        if (assemblies.size > 24) assemblies.keys.take(assemblies.size - 24).forEach { assemblies.remove(it) }
    }

    fun close() {
        listener?.remove(); channel?.unregisterObserver(); channel?.close(); channel?.dispose()
        peer.close(); peer.dispose(); factory.dispose()
    }
}
