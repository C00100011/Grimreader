package com.vdelaar.mylibby.notifications

import android.Manifest
import com.vdelaar.mylibby.core.str
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vdelaar.mylibby.MainActivity
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.core.datastore.GoalSettings
import com.vdelaar.mylibby.core.datastore.GoalType
import com.vdelaar.mylibby.data.StreakCalculator
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Notifications {
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_REMINDERS = "reminders"
    private const val REMINDER_ID = 4242

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_downloads_d)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_reminders_d)
            }
        )
    }

    fun downloadProgress(context: Context, title: String, progress: Float): Notification =
        NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(str(R.string.saving_offline_title))
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .build()

    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showReminder(context: Context, streak: Int, remaining: Long, goal: GoalSettings) {
        if (!canNotify(context)) return
        val unit = str(if (goal.type == GoalType.MINUTES) R.string.unit_min else R.string.unit_pages)
        val title = if (streak > 0) str(R.string.reminder_keep_streak, streak) else str(R.string.reminder_break)
        val text = str(R.string.reminder_text, remaining.toInt(), unit)
        val intent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(REMINDER_ID, n)
    }
}

object ReminderScheduler {
    private const val WORK = "reading-reminder"

    /** Daily check at the configured time; only notifies if the goal isn't met yet. */
    fun schedule(context: Context, goal: GoalSettings) {
        val wm = WorkManager.getInstance(context)
        if (!goal.reminderEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(goal.reminderHour, goal.reminderMinute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MyLibbyApp).container
        val goal = c.settings.goal.value
        if (!goal.reminderEnabled) return Result.success()
        val info = StreakCalculator.compute(c.db.sessions().dayTotals(), goal, LocalDate.now())
        if (!info.todayMet) {
            Notifications.showReminder(applicationContext, info.current, (goal.dailyTarget - info.todayValue).coerceAtLeast(1), goal)
        }
        return Result.success()
    }
}
