package com.ideacode.soundly.debug

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.Binder
import android.os.Process
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Isolated local stand-in for a document provider. Included in debug builds only. */
class I6DocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "i6_set_mode" && Binder.getCallingUid() == Process.myUid()) {
            mode = arg.orEmpty()
            return Bundle.EMPTY
        }
        return super.call(method, arg, extras)
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, "Soundly I6 测试")
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_MIME_TYPES, "audio/*")
            .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery)
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(this, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { if (parentDocumentId == ROOT) addDocument(this, AUDIO) }
    override fun getDocumentType(documentId: String): String = if (documentId == ROOT) Document.MIME_TYPE_DIR else "audio/wav"
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(documentId == AUDIO && mode == "r")
        if (Companion.mode == "denied") throw SecurityException("I6 simulated revoked access")
        if (Companion.mode == "offline") throw java.io.FileNotFoundException("I6 simulated provider unavailable")
        val file = File(requireNotNull(context).cacheDir, "i6-picker-fixture.wav")
        if (!file.exists()) {
            val pcm = ByteBuffer.allocate(44100 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
            repeat(44100 * 3) { pcm.putShort((kotlin.math.sin(it * .03) * 12000).toInt().toShort()) }
            val wav = ByteBuffer.allocate(44 + pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(44100).putInt(88200).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.capacity()).put(pcm.array())
            file.writeBytes(wav.array())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun addDocument(cursor: MatrixCursor, id: String) {
        require(id == ROOT || id == AUDIO)
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, if (id == ROOT) "Soundly I6 测试" else "i6-picker-audio.wav")
            .add(Document.COLUMN_MIME_TYPE, getDocumentType(id))
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, null) // Cloud providers may not know the size in advance.
    }
    companion object {
        private const val ROOT = "i6-root"
        private const val AUDIO = "i6-audio"
        @Volatile private var mode = "normal"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
    }
}
