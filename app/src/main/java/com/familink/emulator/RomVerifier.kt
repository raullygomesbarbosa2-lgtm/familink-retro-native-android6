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
    val bytes: ByteArray,
    val archiveName: String = fileName,
    val archiveBytes: ByteArray = bytes
)

object RomVerifier {
    fun loadZipFromUri(context: Context, uri: Uri, selectedCoreId: String?): VerifiedRom {
        val archiveName = resolveName(context, uri)
        val archiveBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Falha ao abrir arquivo ZIP.")
        return loadZipBytes(archiveName, archiveBytes, selectedCoreId)
    }

    fun loadZipBytes(archiveName: String, archiveBytes: ByteArray, selectedCoreId: String?): VerifiedRom {
        require(archiveName.substringAfterLast('.', "").equals("zip", true)) { "Use somente arquivo .zip na biblioteca." }
        if (archiveBytes.isEmpty()) throw IllegalArgumentException("O arquivo ZIP está vazio.")
        val core = CoreRegistry.cores.firstOrNull { it.id == selectedCoreId }
            ?: throw IllegalArgumentException("Escolha primeiro o sistema/núcleo na aba Núcleos.")
        val entries = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(archiveBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.size != 0L) {
                    val name = entry.name.substringAfterLast('/')
                    val ext = "." + name.substringAfterLast('.', "").lowercase()
                    if (core.id.startsWith("arcade") || core.id == "arcade" || core.id == "neogeo") {
                        // Arcade cores consume the intact ZIP set as one libretro game.
                        break
                    }
                    if (core.extensions.any { it.equals(ext, true) }) entries += name to zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val (gameName, gameBytes) = if (core.id.startsWith("arcade") || core.id == "arcade" || core.id == "neogeo") {
            archiveName to archiveBytes
        } else {
            entries.maxByOrNull { it.second.size } ?: throw IllegalArgumentException("Não achei dentro do ZIP um arquivo compatível com ${core.consoleName}. Formatos esperados: ${core.extensions.joinToString()}.")
        }
        if (gameBytes.isEmpty()) throw IllegalArgumentException("A ROM dentro do ZIP está vazia.")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(gameBytes).joinToString("") { "%02x".format(it) }
        val region = detectRegion("$archiveName $gameName", gameBytes, core.id)
        return VerifiedRom(gameName, core, region, sha256, gameBytes, archiveName, archiveBytes)
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
