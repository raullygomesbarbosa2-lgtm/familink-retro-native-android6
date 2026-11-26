package com.familink.emulator

object NativeCoreBridge {
    init {
        System.loadLibrary("familink_bridge")
    }

    external fun nativeLoadCore(soPath: String): Boolean
    external fun nativeLoadRomBytes(romPath: String, bytes: ByteArray): Boolean
    external fun nativeRunFrame(p1Mask: Int, p2Mask: Int, outArgbPixels: IntArray): Int
    external fun nativeReadAudio(outPcm16: ShortArray): Int
    external fun nativeSaveState(): ByteArray?
    external fun nativeLoadState(stateBytes: ByteArray): Boolean
    external fun nativeUnload()
}
