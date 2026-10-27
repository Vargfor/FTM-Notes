package io.github.vargfor.ftmnotes.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okio.Path

data class PickedFile(val name: String, val path: Path, val mime: String)
data class BackupFile(val name: String, val reference: String, val modifiedAt: Long)
data class SharedInput(val text: String? = null, val files: List<PickedFile> = emptyList(), val checklist: Boolean = false, val workoutId: String? = null)
class AppPlatform(val bridge: PlatformBridge)

/** Capability interface: iOS stays compilable without adding paused platform implementations. */
interface PlatformBridge {
    val canTakePhoto: Boolean get() = false
    val canAutomateBackups: Boolean get() = false
    val filesRoot: Path? get() = null
    val cacheRoot: Path? get() = null
    val incoming: Flow<SharedInput> get() = emptyFlow()
    suspend fun pickFiles(imagesOnly: Boolean = false): List<PickedFile> = unsupported()
    suspend fun takePhoto(): PickedFile? = unsupported()
    suspend fun exportFile(file: Path, name: String, mime: String): Boolean = unsupported()
    suspend fun chooseBackupFolder(): String? = unsupported()
    suspend fun writeBackup(folder: String, file: Path, name: String): Unit = unsupported()
    suspend fun listBackups(folder: String): List<BackupFile> = unsupported()
    suspend fun deleteBackup(reference: String): Unit = unsupported()
    suspend fun readBackup(reference: String): PickedFile = unsupported()
    suspend fun shareText(text: String): Unit = unsupported()
    suspend fun shareFile(file: Path, mime: String): Unit = unsupported()
    suspend fun copyText(text: String): Unit = unsupported()
    suspend fun openFile(file: Path, mime: String): Unit = unsupported()
    fun keepAwake(enabled: Boolean) {}
    suspend fun scheduleTimer(id: String, title: String, endMillis: Long): Unit = unsupported()
    fun cancelTimer(id: String) {}
    fun backupsChanged(enabled: Boolean) {}
    fun workoutChanged(sessionId: String?) {}
    fun elapsedRealtimeMillis(): Long? = null
    fun bootId(): Long? = null
    suspend fun requestWorkoutNotifications(force: Boolean = false) {}
    fun canUseExactTimers(): Boolean = false
    fun requestExactTimers() {}
    fun haptic() {}
}

private val EmptyIncoming = MutableSharedFlow<SharedInput>()
object UnsupportedPlatformBridge : PlatformBridge
private fun unsupported(): Nothing = error("This action is not available on this platform.")
