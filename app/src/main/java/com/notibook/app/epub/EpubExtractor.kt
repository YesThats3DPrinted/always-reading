package com.notibook.app.epub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Extracts an EPUB ZIP archive to a per-book cache directory so that the
 * WebView-based reader can load chapters via `file://` URLs with correct
 * relative path resolution for images, CSS, and inter-chapter links.
 *
 * Cache location: `context.cacheDir/epub_<bookId>/`
 *
 * Extraction is idempotent — if the directory already exists and is non-empty,
 * it is reused as-is. Android may clear the cache dir under storage pressure;
 * the reader calls [ensureExtracted] each time it opens, so re-extraction is
 * transparent to the user.
 */
object EpubExtractor {

    fun getCacheDir(context: Context, bookId: Long): File =
        File(context.cacheDir, "epub_$bookId")

    /**
     * Ensures the EPUB at [epubFilePath] is extracted to the cache dir.
     * Returns the root cache [File] for this book.
     * Runs on [Dispatchers.IO].
     */
    suspend fun ensureExtracted(
        context: Context,
        bookId: Long,
        epubFilePath: String
    ): File = withContext(Dispatchers.IO) {
        val destDir = getCacheDir(context, bookId)
        if (destDir.exists() && destDir.list()?.isNotEmpty() == true) return@withContext destDir
        destDir.mkdirs()

        ZipInputStream(File(epubFilePath).inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().buffered().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        destDir
    }

    /**
     * Deletes the cache directory for [bookId]. Called when a book is removed.
     */
    fun clearCache(context: Context, bookId: Long) {
        getCacheDir(context, bookId).deleteRecursively()
    }
}
