package com.familink.emulator

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.android.gms.tasks.Task

/** Email-account scoped ZIP sync. A real Firebase Storage bucket and UID-scoped rules are required. */
object CloudRomSync {
    fun sync(app: FirebaseApp, context: Context, db: FirebaseFirestore, uid: String, done: (String) -> Unit) {
        val storage = runCatching { FirebaseStorage.getInstance(app) }.getOrElse {
            done("Nuvem indisponível: ${it.localizedMessage ?: "Firebase Storage"}")
            return
        }
        val userFolder = storage.reference.child("user_rom_libraries/$uid")
        // Probe the configured bucket first, so an empty local library is never reported as backed up if Storage is missing.
        userFolder.listAll().addOnSuccessListener {
            uploadAndRestore(storage, context, db, uid, done)
        }.addOnFailureListener { error ->
            done("Backup na nuvem ainda não está ativo: ${error.localizedMessage ?: "Storage não acessível"}. É preciso criar o bucket e publicar as regras do Firebase.")
        }
    }

    private fun uploadAndRestore(storage: FirebaseStorage, context: Context, db: FirebaseFirestore, uid: String, done: (String) -> Unit) {
        val local = RomLibrary.list(context)
        val base = db.collection("user_rom_libraries").document(uid).collection("roms")
        val uploadTasks = mutableListOf<Task<Void>>()
        local.forEach { item ->
            val bytes = runCatching { item.file.readBytes() }.getOrNull()
            if (bytes == null) {
                done("Não consegui ler ${item.archiveName}.")
                return@forEach
            }
            val task = storage.reference.child("user_rom_libraries/$uid/${item.id}.zip").putBytes(bytes)
                .continueWithTask { upload ->
                    if (!upload.isSuccessful) throw upload.exception ?: IllegalStateException("Upload falhou")
                    base.document(item.id).set(mapOf("archiveName" to item.archiveName, "coreId" to item.coreId, "archiveId" to item.id))
                }
            uploadTasks += task
        }
        com.google.android.gms.tasks.Tasks.whenAllComplete(uploadTasks).addOnCompleteListener {
            base.get().addOnSuccessListener { snapshot ->
                val missing = snapshot.documents.filter { doc -> local.none { it.id == doc.id } }
                if (missing.isEmpty()) {
                    val failures = uploadTasks.count { !it.isSuccessful }
                    done(if (failures == 0) "Biblioteca ZIP sincronizada com a conta de e-mail." else "Alguns ZIPs não subiram. Confira as regras e o espaço do Firebase Storage.")
                    return@addOnSuccessListener
                }
                var remaining = missing.size
                var restored = 0
                var failed = false
                missing.forEach { doc ->
                    val name = doc.getString("archiveName") ?: "${doc.id}.zip"
                    val core = doc.getString("coreId") ?: ""
                    storage.reference.child("user_rom_libraries/$uid/${doc.id}.zip").getBytes(100L * 1024L * 1024L)
                        .addOnSuccessListener { bytes ->
                            runCatching { RomLibrary.import(context, name, core, bytes) }
                                .onSuccess { restored++ }
                                .onFailure { failed = true }
                            remaining--
                            if (remaining == 0) done(if (failed) "Parte da biblioteca foi restaurada; alguns ZIPs falharam." else "Biblioteca sincronizada: $restored ZIP(s) restaurado(s) da conta.")
                        }.addOnFailureListener {
                            failed = true; remaining--
                            if (remaining == 0) done("A nuvem não entregou todos os ZIPs. Verifique Storage e regras do Firebase.")
                        }
                }
            }.addOnFailureListener { error -> done("Não consegui ler o índice da biblioteca: ${error.localizedMessage}") }
        }
    }
}
