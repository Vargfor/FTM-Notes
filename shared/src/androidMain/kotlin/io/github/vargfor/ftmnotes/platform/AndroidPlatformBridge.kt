package io.github.vargfor.ftmnotes.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.data.BackupService
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.settings.SettingsRepository
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.*
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit

class AndroidPlatformBridge(private val context: Context) : PlatformBridge {
    override val canTakePhoto = true
    override val canAutomateBackups = true
    override val filesRoot = context.filesDir.absolutePath.toPath()
    override val cacheRoot = context.cacheDir.absolutePath.toPath()
    private val inbox = Channel<SharedInput>(Channel.UNLIMITED)
    override val incoming = inbox.receiveAsFlow()
    private var activity: ComponentActivity? = null
    private val pickerMutex = Mutex()
    private var filesResult: CompletableDeferred<List<Uri>>? = null
    private var uriResult: CompletableDeferred<Uri?>? = null
    private var photoResult: CompletableDeferred<Boolean>? = null
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private var openFiles: ActivityResultLauncher<Array<String>>? = null
    private var saveFile: ActivityResultLauncher<String>? = null
    private var folder: ActivityResultLauncher<Uri?>? = null
    private var camera: ActivityResultLauncher<Uri>? = null
    private var notificationPermission: ActivityResultLauncher<String>? = null

    fun bind(host: ComponentActivity) {
        activity = host
        openFiles = host.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { filesResult?.complete(it) }
        saveFile = host.registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uriResult?.complete(it) }
        folder = host.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uriResult?.complete(it) }
        camera = host.registerForActivityResult(ActivityResultContracts.TakePicture()) { photoResult?.complete(it) }
        notificationPermission = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionResult?.complete(it) }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val training = KoinPlatform.getKoin().get<io.github.vargfor.ftmnotes.data.TrainingService>()
            training.awaitReady()
            workoutChanged(KoinPlatform.getKoin().get<io.github.vargfor.ftmnotes.data.db.TrainingDao>().active()?.sessionId)
        }
    }

    override fun workoutChanged(sessionId: String?) {
        val intent = Intent(context, WorkoutForegroundService::class.java)
        if (sessionId == null) {
            cancelRestAlarm(context)
            context.getSystemService(NotificationManager::class.java).cancel(2402)
            context.stopService(intent)
        }
        else try { context.startForegroundService(intent) }
        catch (e: RuntimeException) { android.util.Log.w("FTM-Training", "Workout remains saved; foreground service could not start", e) }
    }
    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
    override fun bootId(): Long? = androidBootId(context)
    override fun canUseExactTimers() = exactTimersAllowed(context)
    override fun requestExactTimers() {
        if (Build.VERSION.SDK_INT >= 31) activity?.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:" + context.packageName)))
    }
    override fun haptic() { activity?.window?.decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
    override suspend fun requestWorkoutNotifications(force: Boolean) {
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || notificationPermission == null) return
        val permissions = context.getSharedPreferences("runtime-permissions", Context.MODE_PRIVATE)
        if (!force && permissions.getBoolean("training-notifications", false)) return
        pickerMutex.withLock {
            permissions.edit().putBoolean("training-notifications", true).apply()
            val pending = CompletableDeferred<Boolean>(); permissionResult = pending
            try { withContext(Dispatchers.Main) { notificationPermission?.launch(Manifest.permission.POST_NOTIFICATIONS) }; pending.await() }
            finally { permissionResult = null }
        }
    }

    fun unbind(host: ComponentActivity) {
        if (activity === host) {
            activity = null; openFiles = null; saveFile = null; folder = null; camera = null; notificationPermission = null
            filesResult?.cancel(); uriResult?.cancel(); photoResult?.cancel(); permissionResult?.cancel()
        }
    }

    override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> = pickerMutex.withLock {
        val pending = CompletableDeferred<List<Uri>>()
        filesResult = pending
        try {
            withContext(Dispatchers.Main) { requireNotNull(openFiles) { "Open the app to choose files" }.launch(if (imagesOnly) arrayOf("image/*") else arrayOf("*/*")) }
            pending.await().map { copyUri(it) }
        } finally { filesResult = null }
    }

    private suspend fun copyUri(uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "import"
        val target = File(context.cacheDir, "picked/${Ids.newId()}").apply { parentFile!!.mkdirs() }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> target.outputStream().use { input.copyTo(it) } }
        PickedFile(name, target.absolutePath.toPath(), mime)
    }

    override suspend fun takePhoto(): PickedFile? = pickerMutex.withLock {
        val target = File(context.cacheDir, "photos/${Ids.newId()}.jpg").apply { parentFile!!.mkdirs() }
        val uri = fileUri(target)
        val pending = CompletableDeferred<Boolean>()
        photoResult = pending
        try {
            withContext(Dispatchers.Main) { requireNotNull(camera).launch(uri) }
            if (pending.await()) PickedFile(target.name, target.absolutePath.toPath(), "image/jpeg") else null
        } finally { photoResult = null }
    }

    override suspend fun exportFile(file: Path, name: String, mime: String): Boolean = pickerMutex.withLock {
        val pending = CompletableDeferred<Uri?>()
        uriResult = pending
        try {
            withContext(Dispatchers.Main) { requireNotNull(saveFile).launch(name) }
            val uri = pending.await() ?: return@withLock false
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                    File(file.toString()).inputStream().use { it.copyTo(output) }
                }
            }
            true
        } finally { uriResult = null }
    }

    override suspend fun chooseBackupFolder(): String? = pickerMutex.withLock {
        val pending = CompletableDeferred<Uri?>()
        uriResult = pending
        try {
            withContext(Dispatchers.Main) { requireNotNull(folder).launch(null) }
            val uri = pending.await() ?: return@withLock null
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            uri.toString()
        } finally { uriResult = null }
    }

    override suspend fun writeBackup(folder: String, file: Path, name: String) = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val temporary = requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", "$name.partial"))
        try {
            requireNotNull(context.contentResolver.openOutputStream(temporary, "wt")).use { output -> File(file.toString()).inputStream().use { it.copyTo(output) } }
            requireNotNull(DocumentsContract.renameDocument(context.contentResolver, temporary, name)) { "The provider cannot finish a backup file" }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, temporary) }
            throw e
        }
        Unit
    }

    override suspend fun listBackups(folder: String): List<BackupFile> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val result = mutableListOf<BackupFile>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.startsWith("FTM-Notes-") && name.endsWith(".zip")) result += BackupFile(name,
                    DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString(), cursor.getLong(2))
            }
        }
        result
    }

    override suspend fun deleteBackup(reference: String) { withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(reference)) } }
    override suspend fun readBackup(reference: String): PickedFile = copyUri(Uri.parse(reference))
    private fun fileUri(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    override suspend fun shareText(text: String) {
        withContext(Dispatchers.Main) { requireNotNull(activity).startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, null)) }
    }
    override suspend fun copyText(text: String) {
        withContext(Dispatchers.Main) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text)) }
    }
    override suspend fun shareFile(file: Path, mime: String) {
        withContext(Dispatchers.Main) { requireNotNull(activity).startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, fileUri(File(file.toString()))); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null)) }
    }
    override suspend fun openFile(file: Path, mime: String) {
        withContext(Dispatchers.Main) { requireNotNull(activity).startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri(File(file.toString())), mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }) }
    }
    override fun keepAwake(enabled: Boolean) {
        val window = activity?.window ?: return
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override suspend fun scheduleTimer(id: String, title: String, endMillis: Long) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && notificationPermission != null) {
            pickerMutex.withLock {
                val pending = CompletableDeferred<Boolean>(); permissionResult = pending
                try { withContext(Dispatchers.Main) { notificationPermission?.launch(Manifest.permission.POST_NOTIFICATIONS) }; pending.await() }
                finally { permissionResult = null }
            }
        }
        val intent = Intent(context, CookingTimerReceiver::class.java).setData(Uri.parse("ftmnotes://timer/$id")).putExtra("title", title).putExtra("id", id)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, pending)
    }
    override fun cancelTimer(id: String) {
        val intent = Intent(context, CookingTimerReceiver::class.java).setData(Uri.parse("ftmnotes://timer/$id"))
        context.getSystemService(AlarmManager::class.java).cancel(PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return)
    }

    fun receive(intent: Intent) {
        if (intent.action == OPEN_WORKOUT) {
            intent.getStringExtra("workout_id")?.let { inbox.trySend(SharedInput(workoutId = it)) }; return
        }
        if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, "io.github.vargfor.ftmnotes.NEW_NOTE", "io.github.vargfor.ftmnotes.NEW_CHECKLIST")) return
        val copied = Intent(intent)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val uris = mutableListOf<Uri>()
            copied.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(uris::add) } }
            @Suppress("DEPRECATION")
            if (copied.action == Intent.ACTION_SEND_MULTIPLE) copied.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
            else copied.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            val files = uris.distinct().map { copyUri(it) }
            inbox.send(SharedInput(copied.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), files,
                copied.action == "io.github.vargfor.ftmnotes.NEW_CHECKLIST"))
        }
    }

    override fun backupsChanged(enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) manager.cancelUniqueWork("ftm-daily-backup")
        else manager.enqueueUniquePeriodicWork("ftm-daily-backup", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DailyBackupWorker>(1, TimeUnit.DAYS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
    }
}

class DailyBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val koin = KoinPlatform.getKoin()
        val settings = koin.get<SettingsRepository>()
        settings.awaitLoaded()
        if (!settings.snapshot.value[Flags.backupEnabled]) return Result.success()
        return try { koin.get<BackupService>().backupToFolder(); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (e: SecurityException) { Result.failure() }
        catch (e: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}

class CookingTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = "cooking-timers"
                manager.createNotificationChannel(NotificationChannel(channel, getString(Res.string.cook_timer_channel), NotificationManager.IMPORTANCE_HIGH))
                val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                val notification = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(intent.getStringExtra("title")).setContentText(getString(Res.string.cook_timer_finished))
                    .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
                if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    manager.notify(intent.getStringExtra("id").hashCode(), notification)
                }
            } finally { pending.finish() }
        }
    }
}

fun androidPlatformBridge(): AndroidPlatformBridge = KoinPlatform.getKoin().get<PlatformBridge>() as AndroidPlatformBridge
