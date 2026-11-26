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
    val archiveBytes: ByteArray = bytes,
    val launchBytes: ByteArray = bytes,
    val launchFileName: String = fileName
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
        val isArcadeSet = core.id.startsWith("arcade") || core.id == "neogeo"
        var archiveEntryCount = 0
        var archiveHasPayload = false
        val entries = try {
            java.util.zip.ZipInputStream(archiveBytes.inputStream()).use { zip ->
                val found = mutableListOf<Pair<String, ByteArray>>()
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        archiveEntryCount++
                        if (archiveEntryCount > 4096) throw IllegalArgumentException("O ZIP tem itens demais; escolha um conjunto de ROM Arcade válido.")
                        if (isArcadeSet) {
                            // Arcade members can use chip numbers without extensions; the arcade core reads the intact ZIP path.
                            if (entry.size != 0L) archiveHasPayload = true
                            if (entry.size > 128L * 1024L * 1024L) throw IllegalArgumentException("Uma ROM dentro do ZIP passa de 128 MB; não vou abrir um arquivo que pode travar o celular.")
                            zip.closeEntry()
                        } else {
                            val memberName = entry.name.substringAfterLast('/')
                            val ext = "." + memberName.substringAfterLast('.', "").lowercase()
                            if (ext == ".zip" && core.id == "megadrive") {
                                val nested = zip.readBytes()
                                if (nested.size > 128 * 1024 * 1024) throw IllegalArgumentException("O ZIP interno é grande demais para abrir com segurança.")
                                found += extractNested(nested, core, 1)
                            } else if (core.extensions.any { it != ".zip" && it.equals(ext, true) }) {
                                val data = zip.readBytes()
                                if (data.isNotEmpty()) found += memberName to data
                            }
                            zip.closeEntry()
                        }
                    }
                    entry = zip.nextEntry
                }
                found
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("O ZIP está corrompido ou não pôde ser lido: ${e.localizedMessage ?: "arquivo inválido"}")
        }
        if (archiveEntryCount == 0) throw IllegalArgumentException("O ZIP não contém arquivos de jogo.")
        if (isArcadeSet && !archiveHasPayload) throw IllegalArgumentException("Esse ZIP não parece conter ROMs Arcade. Use o ZIP do jogo/ROMset compatível com o núcleo selecionado.")
        if (!isArcadeSet && entries.isEmpty()) throw IllegalArgumentException("Não achei dentro do ZIP uma ROM ${core.consoleName} reconhecível. Ele precisa conter ${core.extensions.filter { it != ".zip" }.joinToString()} (também aceito ZIP interno para Mega Drive).")
        val (gameName, gameBytes) = if (isArcadeSet) {
            archiveName to archiveBytes
        } else {
            entries.maxByOrNull { it.second.size } ?: throw IllegalArgumentException("A ROM compatível dentro do ZIP não foi encontrada.")
        } 
        if (gameBytes.isEmpty()) throw IllegalArgumentException("A ROM selecionada está vazia.")
        val launchBytes = if (isArcadeSet) archiveBytes else gameBytes
        val launchFileName = if (isArcadeSet) archiveName else gameName
        val sha256 = MessageDigest.getInstance("SHA-256").digest(gameBytes).joinToString("") { "%02x".format(it) }
        val region = detectRegion("$archiveName $gameName", gameBytes, core.id)
        return VerifiedRom(gameName, core, region, sha256, gameBytes, archiveName, archiveBytes, launchBytes, launchFileName)
    }

    private fun extractNested(zipBytes: ByteArray, core: AndroidCoreSpec, depth: Int): List<Pair<String, ByteArray>> {
        if (depth > 2) return emptyList()
        val result = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    count++
                    if (count > 4096) throw IllegalArgumentException("O ZIP interno contém itens demais.")
                    val name = entry.name.substringAfterLast('/')
                    val ext = "." + name.substringAfterLast('.', "").lowercase()
                    if (ext == ".zip" && core.id == "megadrive" && depth < 2) {
                        val nested = zip.readBytes()
                        if (nested.size <= 128 * 1024 * 1024) result += extractNested(nested, core, depth + 1)
                    } else if (core.extensions.any { it != ".zip" && it.equals(ext, true) }) {
                        val bytes = zip.readBytes()
                        if (bytes.isNotEmpty() && bytes.size <= 128 * 1024 * 1024) result += name to bytes
                    }
                    zip.closeEntry()
                }
                entry = zip.nextEntry
            }
        }
        return result
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
