package com.ideacode.soundly.feature.export

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Checks restored URIs without assuming a file survived process death or external deletion. */
class AndroidExportedFileVerifier(context: Context) : ExportedFileVerifier {
    private val resolver = context.applicationContext.contentResolver
    override suspend fun exists(uri: String): Boolean = withContext(Dispatchers.IO) {
        val target = Uri.parse(uri)
        when (target.scheme) {
            "content" -> resolver.openFileDescriptor(target, "r")?.use { true } ?: false
            "file" -> target.path?.let { File(it).isFile && File(it).canRead() } ?: false
            else -> false
        }
    }
}
