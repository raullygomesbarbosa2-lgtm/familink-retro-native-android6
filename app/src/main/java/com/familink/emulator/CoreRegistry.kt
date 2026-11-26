package com.familink.emulator

data class AndroidCoreSpec(
    val id: String,
    val consoleName: String,
    val extensions: List<String>,
    val nativeSoName: String,
    val buildbotZipUrl: String
)

object CoreRegistry {
    private val sonyBlocked = setOf(".pbp", ".cso", ".iso", ".ecm", ".mdf", ".psx", ".vpk")

    val cores: List<AndroidCoreSpec> = listOf(
        AndroidCoreSpec("nes", "NES / Famicom", listOf(".nes"), "fceumm_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/fceumm_libretro_android.so.zip"),
        AndroidCoreSpec("fds", "Famicom Disk System", listOf(".fds"), "nestopia_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/nestopia_libretro_android.so.zip"),
        AndroidCoreSpec("snes", "Super Nintendo (SNES)", listOf(".sfc", ".smc"), "snes9x_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/snes9x_libretro_android.so.zip"),
        AndroidCoreSpec("gb", "Game Boy", listOf(".gb"), "gambatte_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/gambatte_libretro_android.so.zip"),
        AndroidCoreSpec("gbc", "Game Boy Color", listOf(".gbc"), "gambatte_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/gambatte_libretro_android.so.zip"),
        AndroidCoreSpec("gba", "Game Boy Advance", listOf(".gba"), "mgba_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mgba_libretro_android.so.zip"),
        AndroidCoreSpec("vb", "Virtual Boy", listOf(".vb", ".vboy"), "mednafen_vb_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mednafen_vb_libretro_android.so.zip"),
        AndroidCoreSpec("gw", "Game & Watch", listOf(".mgw"), "gw_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/gw_libretro_android.so.zip"),
        AndroidCoreSpec("megadrive", "Mega Drive / Genesis", listOf(".md", ".gen", ".smd", ".bin"), "genesis_plus_gx_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/genesis_plus_gx_libretro_android.so.zip"),
        AndroidCoreSpec("sega32x", "Sega 32X", listOf(".32x"), "picodrive_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/picodrive_libretro_android.so.zip"),
        AndroidCoreSpec("segacd", "Sega CD / Mega-CD", listOf(".chd", ".cue"), "genesis_plus_gx_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/genesis_plus_gx_libretro_android.so.zip"),
        AndroidCoreSpec("mastersystem", "Master System", listOf(".sms"), "genesis_plus_gx_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/genesis_plus_gx_libretro_android.so.zip"),
        AndroidCoreSpec("gamegear", "Game Gear", listOf(".gg"), "genesis_plus_gx_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/genesis_plus_gx_libretro_android.so.zip"),
        AndroidCoreSpec("sg1000", "Sega SG-1000", listOf(".sg"), "genesis_plus_gx_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/genesis_plus_gx_libretro_android.so.zip"),
        AndroidCoreSpec("pce", "PC Engine / TurboGrafx-16", listOf(".pce", ".sgx"), "mednafen_pce_fast_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mednafen_pce_fast_libretro_android.so.zip"),
        AndroidCoreSpec("ngp", "Neo Geo Pocket", listOf(".ngp", ".ngc"), "mednafen_ngp_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mednafen_ngp_libretro_android.so.zip"),
        AndroidCoreSpec("arcade", "Arcade / CPS1-3 (FBNeo)", listOf(".zip"), "fbneo_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/fbneo_libretro_android.so.zip"),
        AndroidCoreSpec("arcade2000", "Arcade 2000", listOf(".zip"), "mame2000_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mame2000_libretro_android.so.zip"),
        AndroidCoreSpec("arcade2003", "Arcade 2003", listOf(".zip"), "mame2003_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mame2003_libretro_android.so.zip"),
        AndroidCoreSpec("arcade2003plus", "Arcade 2003 Plus", listOf(".zip"), "mame2003_plus_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mame2003_plus_libretro_android.so.zip"),
        AndroidCoreSpec("arcade2010", "Arcade 2010", listOf(".zip"), "mame2010_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mame2010_libretro_android.so.zip"),
        AndroidCoreSpec("arcade2013", "Arcade 2013", listOf(".zip"), "fbalpha2012_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/fbalpha2012_libretro_android.so.zip"),
        AndroidCoreSpec("arcade2013plus", "Arcade 2013 Plus", listOf(".zip"), "mame2015_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mame2015_libretro_android.so.zip"),
        AndroidCoreSpec("neogeo", "Neo Geo AES/MVS/CD", listOf(".neo", ".zip"), "fbneo_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/fbneo_libretro_android.so.zip"),
        AndroidCoreSpec("lynx", "Atari Lynx", listOf(".lnx"), "handy_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/handy_libretro_android.so.zip"),
        AndroidCoreSpec("wonderswan", "WonderSwan / Color", listOf(".ws", ".wsc"), "mednafen_wswan_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mednafen_wswan_libretro_android.so.zip"),
        AndroidCoreSpec("colecovision", "ColecoVision", listOf(".col"), "gearcoleco_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/gearcoleco_libretro_android.so.zip"),
        AndroidCoreSpec("vectrex", "GCE Vectrex", listOf(".vec"), "vecx_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/vecx_libretro_android.so.zip"),
        AndroidCoreSpec("odyssey2", "Odyssey 2 / Videopac", listOf(".o2"), "o2em_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/o2em_libretro_android.so.zip"),
        AndroidCoreSpec("channelf", "Fairchild Channel F", listOf(".chf"), "freechaf_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/freechaf_libretro_android.so.zip"),
        AndroidCoreSpec("3do", "3DO Multiplayer", listOf(".chd", ".cue"), "opera_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/opera_libretro_android.so.zip"),
        AndroidCoreSpec("c64", "Commodore 64 / VIC-20", listOf(".d64", ".t64", ".prg", ".crt"), "vice_x64_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/vice_x64_libretro_android.so.zip"),
        AndroidCoreSpec("pc88", "NEC PC-8801", listOf(".d88", ".t88"), "quasi88_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/quasi88_libretro_android.so.zip"),
        AndroidCoreSpec("sharpx1", "Sharp X1", listOf(".dx1", ".2d", ".tap"), "x1_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/x1_libretro_android.so.zip"),
        AndroidCoreSpec("thomson", "Thomson MO/TO", listOf(".fd", ".sap", ".k7"), "theodore_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/theodore_libretro_android.so.zip"),
        AndroidCoreSpec("p2000", "Philips P2000T", listOf(".cas"), "m2000_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/m2000_libretro_android.so.zip"),
        AndroidCoreSpec("mac", "Macintosh Plus", listOf(".dsk"), "minivmac_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/minivmac_libretro_android.so.zip"),
        AndroidCoreSpec("bk", "Elektronika BK", listOf(".bk"), "bk_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/bk_libretro_android.so.zip"),
        AndroidCoreSpec("galaksija", "Galaksija", listOf(".gtp"), "galaksija_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/galaksija_libretro_android.so.zip"),
        AndroidCoreSpec("pico8", "PICO-8", listOf(".p8", ".p8.png"), "retro8_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/retro8_libretro_android.so.zip"),
        AndroidCoreSpec("tic80", "TIC-80", listOf(".tic"), "tic80_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/tic80_libretro_android.so.zip"),
        AndroidCoreSpec("wasm4", "WASM-4", listOf(".wasm"), "wasm4_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/wasm4_libretro_android.so.zip"),
        AndroidCoreSpec("arduboy", "Arduboy", listOf(".hex", ".arduboy"), "arduous_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/arduous_libretro_android.so.zip"),
        AndroidCoreSpec("uzebox", "Uzebox", listOf(".uze"), "uzem_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/uzem_libretro_android.so.zip"),
        AndroidCoreSpec("doom", "Doom / Quake / Engines", listOf(".wad", ".pak", ".wl6", ".nx", ".uw8", ".vspec"), "prboom_libretro_android.so", "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/prboom_libretro_android.so.zip")
    )

    fun detectForFilename(fileName: String): AndroidCoreSpec {
        val lower = fileName.lowercase()
        for (blocked in sonyBlocked) {
            if (lower.endsWith(blocked)) {
                throw IllegalArgumentException("Consoles Sony ($blocked) não são permitidos nesta build.")
            }
        }
        return cores.firstOrNull { spec -> spec.extensions.any { lower.endsWith(it) } }
            ?: throw IllegalArgumentException("Extensão não suportada: $fileName")
    }
}
