package com.familink.emulator

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** App-local launcher library. Cloud backup requires the Firebase Storage bucket to exist and permit UID-scoped access. */
data class LibraryRom(val id: String, val archiveName: String, val coreId: String, val file: File)

object RomLibrary {
    private fun root(context: Context) = File(context.filesDir, "rom-library").apply { mkdirs() }

    fun import(context: Context, archiveName: String, coreId: String, bytes: ByteArray): LibraryRom {
        require(archiveName.substringAfterLast('.', "").equals("zip", ignoreCase = true)) { "A biblioteca aceita apenas arquivos .zip." }
        val digestInput = coreId.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + bytes
        val id = MessageDigest.getInstance("SHA-256").digest(digestInput).joinToString("") { "%02x".format(it) }
        val directory = root(context)
        val zip = File(directory, "$id.zip")
        if (!zip.exists() || zip.length() != bytes.size.toLong()) zip.writeBytes(bytes)
        File(directory, "$id.meta").writeText("$coreId\n$archiveName")
        return LibraryRom(id, archiveName, coreId, zip)
    }

    fun list(context: Context): List<LibraryRom> = root(context).listFiles()
        ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
        ?.mapNotNull { file ->
            val id = file.nameWithoutExtension
            val meta = File(file.parentFile, "$id.meta")
            if (!meta.exists()) return@mapNotNull null
            val lines = runCatching { meta.readLines() }.getOrNull() ?: return@mapNotNull null
            if (lines.size < 2) return@mapNotNull null
            LibraryRom(id, lines.drop(1).joinToString("\n"), lines[0], file)
        }?.sortedBy { it.archiveName.lowercase() } ?: emptyList()

    fun find(context: Context, id: String): LibraryRom? = list(context).firstOrNull { it.id == id }

    fun delete(context: Context, id: String) {
        val dir = root(context)
        File(dir, "$id.zip").delete()
        File(dir, "$id.meta").delete()
    }
}
