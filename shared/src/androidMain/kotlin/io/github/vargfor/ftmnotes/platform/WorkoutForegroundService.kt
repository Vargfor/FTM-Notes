package io.github.vargfor.ftmnotes.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import io.github.vargfor.ftmnotes.data.*
import io.github.vargfor.ftmnotes.data.db.*
import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

private const val ACTIVE_CHANNEL = "training-active"
private const val ALERT_CHANNEL = "training-rest"
private const val ACTIVE_NOTIFICATION = 2401
private const val REST_NOTIFICATION = 2402
internal const val OPEN_WORKOUT = "io.github.vargfor.ftmnotes.RESUME_WORKOUT"
private const val REST_ACTION = "io.github.vargfor.ftmnotes.REST_ENDED"
private const val EXTEND_ACTION = "io.github.vargfor.ftmnotes.EXTEND_REST"
private const val SKIP_ACTION = "io.github.vargfor.ftmnotes.SKIP_REST"

/** Persists no parallel state: notifications, alarms and the coroutine all use the singleton DB row. */
class WorkoutForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        runBlocking { createTrainingChannels(this@WorkoutForegroundService) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = runBlocking { baseNotification(this@WorkoutForegroundService, null)
            .setContentTitle(getString(Res.string.training_active_notification)).build() }
        if (Build.VERSION.SDK_INT >= 34) startForeground(ACTIVE_NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ACTIVE_NOTIFICATION, initial)
        scope.launch {
            val koin = KoinPlatform.getKoin()
            val training = koin.get<TrainingService>()
            training.awaitReady()
            when (intent?.action) {
                EXTEND_ACTION -> training.extendRest(15)
                SKIP_ACTION -> training.skipRest()
            }
            if (observer == null) observer = scope.launch {
                val dao = koin.get<TrainingDao>()
                val repository = koin.get<DocumentRepository>()
                dao.observeActive().collectLatest { state ->
                    releaseWake()
                    cancelRestAlarm(this@WorkoutForegroundService)
                    val session = state?.let { repository.get(it.sessionId)?.document as? WorkoutSessionDocument }
                    if (state == null || session == null || session.status != SessionStatus.InProgress) {
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collectLatest
                    }
                    val builder = baseNotification(this@WorkoutForegroundService, state.sessionId)
                        .setContentTitle(session.title.ifBlank { getString(Res.string.training_active_notification) })
                        .setWhen(session.startedAt?.toEpochMilliseconds() ?: Clock.System.now().toEpochMilliseconds())
                        .setUsesChronometer(true).setShowWhen(true)
                    val remaining = TrainingService.remainingMillis(state, Clock.System.now().toEpochMilliseconds(),
                        SystemClock.elapsedRealtime(), androidBootId(this@WorkoutForegroundService))
                    if (state.restEnd != null && state.alertedToken != state.restToken) {
                        builder.setWhen(Clock.System.now().toEpochMilliseconds() + remaining.coerceAtLeast(0)).setChronometerCountDown(true)
                            .setContentText(getString(Res.string.training_rest_until, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(state.restEnd))))
                            .addAction(Notification.Action.Builder(null, getString(Res.string.training_extend_rest), serviceAction(EXTEND_ACTION)).build())
                            .addAction(Notification.Action.Builder(null, getString(Res.string.training_skip_rest), serviceAction(SKIP_ACTION)).build())
                    } else builder.setContentText(getString(Res.string.training_resume_notification))
                    getSystemService(NotificationManager::class.java).notify(ACTIVE_NOTIFICATION, builder.build())
                    if (state.restEnd != null && state.restToken != null && state.alertedToken != state.restToken) {
                        scheduleRestAlarm(this@WorkoutForegroundService, state)
                        if (remaining > 0 && !exactTimersAllowed(this@WorkoutForegroundService)) {
                            // The lock only covers an actual rest, and always has a bounded OS timeout.
                            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + ":rest").apply {
                                setReferenceCounted(false); acquire((remaining + 5000).coerceIn(1000, 15 * 60 * 1000))
                            }
                        }
                        try { delay(remaining.coerceAtLeast(0)); deliverRestAlert(this@WorkoutForegroundService, state.restToken) }
                        finally { releaseWake() }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun serviceAction(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, WorkoutForegroundService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null }
    override fun onDestroy() { scope.cancel(); releaseWake(); super.onDestroy() }
}

internal fun androidBootId(context: Context): Long? = runCatching {
    android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT).toLong()
}.getOrNull()
internal fun exactTimersAllowed(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private suspend fun createTrainingChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ACTIVE_CHANNEL, getString(Res.string.training_active_channel), NotificationManager.IMPORTANCE_LOW))
    manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, getString(Res.string.training_alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(true)
    })
}
private fun openWorkout(context: Context, id: String?): PendingIntent {
    val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        .setAction(OPEN_WORKOUT).putExtra("workout_id", id)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(context, 2401, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
private fun baseNotification(context: Context, id: String?) = Notification.Builder(context, ACTIVE_CHANNEL)
    .setSmallIcon(android.R.drawable.ic_menu_recent_history).setOngoing(true).setOnlyAlertOnce(true)
    .setContentIntent(openWorkout(context, id)).setCategory(Notification.CATEGORY_WORKOUT)

private fun alarmIntent(context: Context, token: String? = null) = PendingIntent.getBroadcast(context, 2402,
    Intent(context, WorkoutTimerReceiver::class.java).setAction(REST_ACTION).setData(Uri.parse("ftmnotes://training/rest")).putExtra("token", token),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
internal fun cancelRestAlarm(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context)) }
internal fun scheduleRestAlarm(context: Context, state: ActiveWorkoutEntity) {
    if (state.restEnd == null || state.restToken == null || state.restToken == state.alertedToken) return
    val elapsed = state.elapsedEnd != null && state.bootId != null && state.bootId == androidBootId(context)
    val type = if (elapsed) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP
    val deadline = if (elapsed) state.elapsedEnd!! else state.restEnd
    val alarms = context.getSystemService(AlarmManager::class.java)
    val pending = alarmIntent(context, state.restToken)
    try {
        if (exactTimersAllowed(context)) alarms.setExactAndAllowWhileIdle(type, deadline, pending)
        else alarms.setAndAllowWhileIdle(type, deadline, pending)
    } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(type, deadline, pending) }
}

private suspend fun deliverRestAlert(context: Context, token: String) = withContext(NonCancellable) {
    val koin = KoinPlatform.getKoin()
    val training = koin.get<TrainingService>()
    training.awaitReady()
    createTrainingChannels(context)
    val id = koin.get<TrainingDao>().active()?.sessionId ?: return@withContext
    val notification = Notification.Builder(context, ALERT_CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(getString(Res.string.training_timer_finished)).setContentText(getString(Res.string.training_resume_notification))
        .setContentIntent(openWorkout(context, id)).setAutoCancel(true).setCategory(Notification.CATEGORY_ALARM).build()
    if (!training.claimRestAlert(token)) return@withContext
    if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        context.getSystemService(NotificationManager::class.java).notify(REST_NOTIFICATION, notification)
}

/** The persistent token makes alarm and foreground deliveries idempotent, including stale broadcasts. */
class WorkoutTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    val koin = KoinPlatform.getKoin()
                    koin.get<TrainingService>().awaitReady()
                    koin.get<TrainingDao>().active()?.let { state ->
                        scheduleRestAlarm(context, state.copy(elapsedEnd = null, bootId = null))
                    }
                } else intent.getStringExtra("token")?.let { deliverRestAlert(context, it) }
            } finally { pending.finish() }
        }
    }
}
