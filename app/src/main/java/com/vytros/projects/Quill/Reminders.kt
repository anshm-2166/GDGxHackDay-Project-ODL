package com.vytros.projects.Quill

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.DateFormat
import java.util.Date

/** Alarm-backed reminders for events saved with /addevent and /remind. */
object Reminders {
    private const val CHANNEL = "reminders"

    private fun pending(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, (id % Int.MAX_VALUE).toInt(),
        Intent(ctx, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canBeExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Returns true if the alarm is exact, false if Android only allows an approximate one. */
    fun schedule(ctx: Context, e: Store.Event, now: Long = System.currentTimeMillis()): Boolean {
        var at = e.startMs - e.remindMin * 60_000L
        if (at < now) at = if (e.startMs > now) now + 2_000 else return true // already past
        val am = ctx.getSystemService(AlarmManager::class.java)
        return if (canBeExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, e.id)); true
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, e.id)); false
        }
    }

    fun cancel(ctx: Context, id: Long) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))

    fun rescheduleAll(ctx: Context) = Store.upcoming(ctx).forEach { schedule(ctx, it) }

    fun whenText(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun notify(ctx: Context, e: Store.Event) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val minutes = ((e.startMs - System.currentTimeMillis()) / 60_000).toInt()
        val detail = buildString {
            append(if (minutes > 1) "In $minutes min" else "Now")
            append(" · ").append(whenText(e.startMs))
            if (e.location.isNotBlank()) append(" · ").append(e.location)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_scribe)
            .setContentTitle(e.title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (e.notes.isBlank()) detail else "$detail\n${e.notes}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify((e.id % Int.MAX_VALUE).toInt(), n) } // may be refused if notifications are off
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.event(ctx, intent.getLongExtra("id", -1))?.let { Reminders.notify(ctx, it) }
    }
}

/** Alarms don't survive a reboot, so put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rescheduleAll(ctx)
    }
}
