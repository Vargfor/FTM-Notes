package io.github.vargfor.ftmnotes.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import okio.Path
import okio.Path.Companion.toPath
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane

/** Functional file/clipboard adapters for development; Android owns background scheduling. */
class DesktopPlatformBridge : PlatformBridge {
    override val filesRoot = (System.getenv("FTM_DEV_DIR") ?: File(System.getProperty("user.home"), ".ftm-notes-dev").absolutePath).toPath()
    override val cacheRoot = filesRoot / "cache"
    override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> = withContext(Dispatchers.Swing) {
        val picker = JFileChooser().apply { isMultiSelectionEnabled = true }
        if (picker.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) emptyList()
        else picker.selectedFiles.map { PickedFile(it.name, it.absolutePath.toPath(), Files.probeContentType(it.toPath()) ?: "application/octet-stream") }
    }
    override suspend fun exportFile(file: Path, name: String, mime: String): Boolean {
        val target = withContext(Dispatchers.Swing) {
            val picker = JFileChooser().apply { selectedFile = File(name) }
            if (picker.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) null
            else picker.selectedFile.takeIf { !it.exists() || JOptionPane.showConfirmDialog(null, it.name, name, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION }
        } ?: return false
        withContext(Dispatchers.IO) { Files.copy(File(file.toString()).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        return true
    }
    override suspend fun chooseBackupFolder(): String? = withContext(Dispatchers.Swing) {
        val picker = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFile.absolutePath else null
    }
    override suspend fun writeBackup(folder: String, file: Path, name: String) {
        withContext(Dispatchers.IO) {
            val temporary = File(folder, "$name.partial")
            Files.copy(File(file.toString()).toPath(), temporary.toPath())
            Files.move(temporary.toPath(), File(folder, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }
    override suspend fun listBackups(folder: String): List<BackupFile> = withContext(Dispatchers.IO) {
        File(folder).listFiles().orEmpty().filter { it.isFile && it.name.startsWith("FTM-Notes-") && it.name.endsWith(".zip") }
            .map { BackupFile(it.name, it.absolutePath, it.lastModified()) }
    }
    override suspend fun deleteBackup(reference: String) { withContext(Dispatchers.IO) { Files.deleteIfExists(File(reference).toPath()) } }
    override suspend fun readBackup(reference: String): PickedFile = PickedFile(File(reference).name, reference.toPath(), "application/zip")
    override suspend fun copyText(text: String) { withContext(Dispatchers.Swing) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }
    override suspend fun shareText(text: String) = copyText(text)
    override suspend fun shareFile(file: Path, mime: String) = openFile(file, mime)
    override suspend fun openFile(file: Path, mime: String) { withContext(Dispatchers.IO) { Desktop.getDesktop().open(File(file.toString())) } }
}
