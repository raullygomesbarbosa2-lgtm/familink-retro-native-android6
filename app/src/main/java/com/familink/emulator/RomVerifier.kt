package com.familink.emulator

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest

enum class RomRegion { USA, JPN, EUR, WORLD }

data class VerifiedRom(
    val fileName: String,
    val coreSpec: AndroidCoreSpec,
    val region: RomRegion,
    val sha256: String,
    val bytes: ByteArray
)

object RomVerifier {
    fun loadFromUri(context: Context, uri: Uri): VerifiedRom {
        val fileName = resolveName(context, uri)
        val coreSpec = CoreRegistry.detectForFilename(fileName)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Falha ao abrir arquivo ROM.")

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha256 = digest.joinToString("") { "%02x".format(it) }
        val region = detectRegion(fileName, bytes, coreSpec.id)

        return VerifiedRom(fileName, coreSpec, region, sha256, bytes)
    }

    fun validateMatch(hostHash: String, hostRegion: RomRegion, guest: VerifiedRom): Pair<Boolean, String> {
        if (guest.region != hostRegion) {
            return false to "Incompatível! Sala exige ${hostRegion.name}, mas sua ROM é ${guest.region.name}."
        }
        if (guest.sha256 != hostHash) {
            return false to "ROM diferente! SHA-256 do P2 não corresponde à ROM do Host P1."
        }
        return true to "ROM e Região verificadas!"
    }

    private fun detectRegion(name: String, bytes: ByteArray, consoleId: String): RomRegion {
        val upper = name.uppercase()
        return when {
            Regex("\\\\(J\\\\)|\\\\(JAPAN\\\\)|\\\\(JP\\\\)|FAMICOM").containsMatchIn(upper) -> RomRegion.JPN
            Regex("\\\\(U\\\\)|\\\\(USA\\\\)|\\\\(US\\\\)").containsMatchIn(upper) -> RomRegion.USA
            Regex("\\\\(E\\\\)|\\\\(EUROPE\\\\)|\\\\(PAL\\\\)").containsMatchIn(upper) -> RomRegion.EUR
            Regex("\\\\(W\\\\)|\\\\(WORLD\\\\)").containsMatchIn(upper) -> RomRegion.WORLD
            (consoleId == "gb" || consoleId == "gbc") && bytes.size > 0x14A ->
                if (bytes[0x14A].toInt() == 0) RomRegion.JPN else RomRegion.USA
            consoleId == "gba" && bytes.size > 0xB0 -> when (bytes[0xAF].toInt().toChar()) {
                'J' -> RomRegion.JPN
                'P', 'D', 'F' -> RomRegion.EUR
                else -> RomRegion.USA
            }
            else -> RomRegion.USA
        }
    }

    private fun resolveName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) return cursor.getString(idx)
        }
        return uri.lastPathSegment ?: "game.rom"
    }
}
