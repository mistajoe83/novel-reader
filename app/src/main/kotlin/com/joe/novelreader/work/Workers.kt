package com.joe.novelreader.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.joe.novelreader.MainActivity
import com.joe.novelreader.NovelApp
import com.joe.novelreader.R
import com.joe.novelreader.data.ChapterEntity
import com.joe.novelreader.data.DownloadEntity
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

object Notifications {
    const val DOWNLOADS = "downloads"
    const val UPDATES = "updates"
    const val DOWNLOAD_ID = 1001
    const val UPDATE_ID = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(UPDATES, "New chapters", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class DownloadManager(private val context: Context) {
    private val app get() = context.applicationContext as NovelApp

    suspend fun enqueue(novelTitle: String, chapters: List<ChapterEntity>) {
        val now = System.currentTimeMillis()
        val items = chapters.filter { !it.downloaded }.map {
            DownloadEntity(it.id, it.novelId, novelTitle, it.name, it.idx, now)
        }
        if (items.isEmpty()) return
        app.container.db.downloads().enqueue(items)
        start()
    }

    fun start() {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("downloads", ExistingWorkPolicy.KEEP, req)
    }

    fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork("downloads")
    }

    suspend fun retryFailed() {
        app.container.db.downloads().retryFailed()
        start()
    }

    suspend fun clearQueue() {
        stop()
        app.container.db.downloads().clear()
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as NovelApp

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Downloading chapters", 0, 0)

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifications.DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading chapters")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(Notifications.openAppIntent(applicationContext))
            .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.DOWNLOAD_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.DOWNLOAD_ID, n)
        }
    }

    override suspend fun doWork(): Result {
        val dao = app.container.db.downloads()
        val repo = app.container.repository
        var done = 0
        runCatching { setForeground(foreground("Starting…", 0, 0)) }
        while (!isStopped) {
            val item = dao.nextPending() ?: break
            val remaining = dao.pendingCount()
            runCatching { setForeground(foreground("${item.novelTitle}: ${item.chapterName}", done, done + remaining)) }
            val chapter = repo.getChapter(item.chapterId)
            if (chapter == null) {
                dao.remove(item.chapterId)
                continue
            }
            var attempt = 0
            while (true) {
                try {
                    repo.downloadChapter(chapter)
                    dao.remove(item.chapterId)
                    done++
                    break
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 3) {
                        dao.fail(item.chapterId, e.message ?: e.javaClass.simpleName)
                        break
                    }
                    delay(1500L * attempt)
                }
            }
            delay(250) // be gentle with the sites
        }
        return Result.success()
    }
}

/** Checks library books for new chapters in the background. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as NovelApp

    override suspend fun doWork(): Result {
        val found = app.container.checkLibraryUpdates()
        if (found.isNotEmpty() && Notifications.canNotify(applicationContext)) {
            val total = found.sumOf { it.second }
            val text = found.take(5).joinToString("\n") { "${it.first}: ${it.second} new" }
            val n = NotificationCompat.Builder(applicationContext, Notifications.UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$total new chapter${if (total == 1) "" else "s"}")
                .setContentText(found.joinToString { it.first })
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(Notifications.openAppIntent(applicationContext))
                .build()
            try {
                NotificationManagerCompat.from(applicationContext).notify(Notifications.UPDATE_ID, n)
            } catch (_: SecurityException) {
            }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork("library-updates")
                return
            }
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork("library-updates", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
