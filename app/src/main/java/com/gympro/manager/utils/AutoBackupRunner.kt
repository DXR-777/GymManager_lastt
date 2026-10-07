package com.gympro.manager.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gympro.manager.data.repository.GymRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object AutoBackupRunner {

    const val KEEP_COUNT = 8
    private const val PREFIX = "gym_backup_"
    private const val EXT = ".gymbak"

    suspend fun run(context: Context, repository: GymRepository): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            if (!BackupPrefs.isAutoEnabled(app)) return@withContext false
            val tree = BackupPrefs.treeUri(app) ?: throw IOException("no_folder")
            val granted = app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
            if (!granted) throw IOException("no_permission")
            val password = BackupPrefs.password(app) ?: throw IOException("no_password")
            val local = BackupManager.createBackupFile(app, repository, password)
            try {
                copyToTree(app, tree, local)
            } finally {
                local.delete()
            }
            prune(app, tree)
            BackupPrefs.markAutoResult(app, true)
            true
        } catch (e: Exception) {
            CrashReporter.record(e)
            BackupPrefs.markAutoResult(app, false, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun treeRoot(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun copyToTree(context: Context, tree: Uri, file: File) {
        val resolver = context.contentResolver
        val doc = DocumentsContract.createDocument(resolver, treeRoot(tree), "application/octet-stream", file.name)
            ?: throw IOException("create_failed")
        val out = resolver.openOutputStream(doc, "w") ?: throw IOException("open_failed")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    private fun prune(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = mutableListOf<Pair<String, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(EXT)) found.add(c.getString(0) to name)
            }
        }
        found.sortedByDescending { it.second }.drop(KEEP_COUNT).forEach { (id, _) ->
            try {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            } catch (_: Exception) {
            }
        }
    }
}
