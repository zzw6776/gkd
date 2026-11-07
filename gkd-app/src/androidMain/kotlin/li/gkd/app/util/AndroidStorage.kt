package li.gkd.app.util

import java.io.File
import li.gkd.app.app
import li.gkd.app.storage.AppStorageLayout
import li.gkd.app.storage.DirectorySelectionRecord
import li.gkd.app.storage.StorageLocation
import li.gkd.app.storage.StorageSelectionReason
import li.gkd.app.storage.recordStartupLog

object AndroidStorage {
    private data class StorageInitialization(
        val filesDir: File,
        val directorySelectionRecord: DirectorySelectionRecord,
    )

    private val initialization: StorageInitialization by lazy {
        val internalFiles = app.filesDir
        val markFile = internalFiles.resolve(".gkd")
        val marked = markFile.isFile
        val external =
            checkNotNull(app.getExternalFilesDir(null)) {
                "External app storage is unavailable; refusing to switch data directories"
            }
        val record =
            DirectorySelectionRecord(
                markerBefore = marked,
                externalResult = external.absolutePath,
                reason = StorageSelectionReason.ExternalAvailable,
                markerCreated = null,
                selectedPath = external.absolutePath,
                storageLocation = StorageLocation.External,
            )
        recordStartupLog(record, internalFiles, external)
        StorageInitialization(external, record)
    }

    val filesDir
        get() = initialization.filesDir

    val directorySelectionRecord
        get() = initialization.directorySelectionRecord

    private val cacheDir by lazy { app.externalCacheDir ?: app.cacheDir }

    val storage by lazy { AppStorageLayout(filesDir, cacheDir, app.filesDir) }
    val privilegeCrashDirectory by lazy { app.getExternalFilesDir("priv-crash") }
}
