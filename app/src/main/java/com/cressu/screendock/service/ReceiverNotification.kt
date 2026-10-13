package com.cressu.screendock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import com.cressu.screendock.R
import com.cressu.screendock.ui.MainActivity

/** Everything the notification text depends on. */
data class NotificationModel(
    val enabled: Boolean,
    val state: ReceiverState,
    val mode: MediaMode,
    val sender: String?,
    val trackTitle: String?,
    val pin: String?,
    val error: String?,
    val starting: Boolean = false,
)

/** String lookups, abstracted so [NotificationCopy] is a pure function (unit-testable). */
interface NotificationTexts {
    fun ready(): String
    fun starting(): String
    fun stopped(): String
    fun error(): String
    fun connected(sender: String): String
    fun mirroring(sender: String): String
    fun playing(title: String): String
    fun playingFrom(sender: String): String
    fun videoFrom(sender: String): String
    fun pin(pin: String): String
}

object NotificationCopy {
    /** Title line for the persistent notification. */
    fun title(m: NotificationModel, t: NotificationTexts): String {
        val who = m.sender?.takeIf { it.isNotBlank() }
        return when {
            !m.enabled -> t.stopped()
            m.error != null && m.state == ReceiverState.IDLE -> t.error()
            m.pin != null -> t.pin(m.pin)
            m.state == ReceiverState.CONNECTED -> when (m.mode) {
                MediaMode.MIRROR -> who?.let(t::mirroring) ?: t.ready()
                MediaMode.VIDEO -> m.trackTitle?.takeIf { it.isNotBlank() }?.let(t::playing)
                    ?: who?.let(t::videoFrom) ?: t.ready()
                MediaMode.AUDIO -> m.trackTitle?.takeIf { it.isNotBlank() }?.let(t::playing)
                    ?: who?.let(t::playingFrom) ?: t.ready()
                MediaMode.NONE -> who?.let(t::connected) ?: t.ready()
            }
            m.state == ReceiverState.WAITING -> t.ready()
            else -> t.starting()
        }
    }
}

/** Builds/updates the foreground, alert, approval and boot-prompt notifications. */
class ReceiverNotification(private val ctx: Context) : NotificationTexts {

    private val nm = ctx.getSystemService(NotificationManager::class.java)

    override fun ready() = ctx.getString(R.string.notif_title_ready)
    override fun starting() = ctx.getString(R.string.notif_title_starting)
    override fun stopped() = ctx.getString(R.string.notif_title_stopped)
    override fun error() = ctx.getString(R.string.notif_title_error)
    override fun connected(sender: String) = ctx.getString(R.string.notif_title_connected, sender)
    override fun mirroring(sender: String) = ctx.getString(R.string.notif_title_mirroring, sender)
    override fun playing(title: String) = ctx.getString(R.string.notif_title_playing, title)
    override fun playingFrom(sender: String) = ctx.getString(R.string.notif_title_playing_from, sender)
    override fun videoFrom(sender: String) = ctx.getString(R.string.notif_title_video_from, sender)
    override fun pin(pin: String) = ctx.getString(R.string.notif_title_pin, pin)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_RECEIVER, ctx.getString(R.string.notif_channel_receiver), NotificationManager.IMPORTANCE_LOW
        ).apply { description = ctx.getString(R.string.notif_channel_receiver_desc) })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_CONNECT, ctx.getString(R.string.notif_channel_connect), NotificationManager.IMPORTANCE_HIGH
        ).apply { description = ctx.getString(R.string.notif_channel_connect_desc) })
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(ctx, channel)
        else @Suppress("DEPRECATION") Notification.Builder(ctx)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(action: String, req: Int, foreground: Boolean = false): PendingIntent {
        val i = Intent(ctx, AirPlayService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(ctx, req, i, flags)
        else PendingIntent.getService(ctx, req, i, flags)
    }

    private fun action(titleRes: Int, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_stat_screendock), ctx.getString(titleRes), pi,
        ).build()

    /** The persistent foreground notification. */
    fun buildForeground(
        m: NotificationModel,
        displayName: String,
        cover: Bitmap?,
        sessionToken: MediaSession.Token?,
        artist: String?,
    ): Notification {
        val title = NotificationCopy.title(m, this)
        val text = when {
            m.pin != null -> ctx.getString(R.string.notif_text_pin)
            m.error != null -> m.error
            !m.enabled -> ctx.getString(R.string.notif_text_stopped)
            m.state == ReceiverState.CONNECTED && !artist.isNullOrBlank() -> artist
            else -> ctx.getString(R.string.notif_text_advertising, displayName)
        }
        val b = builder(CHANNEL_RECEIVER)
            .setSmallIcon(R.drawable.ic_stat_screendock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                builder(CHANNEL_RECEIVER).setSmallIcon(R.drawable.ic_stat_screendock)
                    .setContentTitle(if (m.pin != null) ctx.getString(R.string.notif_pin_public) else title).build()
            )
        if (cover != null) b.setLargeIcon(cover)
        if (m.enabled) {
            b.addAction(action(R.string.notif_action_stop, serviceAction(AirPlayService.ACTION_STOP, 1)))
        } else {
            b.addAction(action(R.string.notif_action_start, serviceAction(AirPlayService.ACTION_USER_START, 2, true)))
        }
        if (sessionToken != null && m.mode == MediaMode.AUDIO) {
            b.setStyle(Notification.MediaStyle().setMediaSession(sessionToken).setShowActionsInCompactView(0))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    fun notifyForeground(n: Notification) = safeNotify(NOTIF_FOREGROUND, n)

    /** Dismissible "receiver stopped, tap Start" card shown after a user Stop from the notification. */
    fun notifyStopped() {
        val n = builder(CHANNEL_RECEIVER)
            .setSmallIcon(R.drawable.ic_stat_screendock)
            .setContentTitle(stopped())
            .setContentText(ctx.getString(R.string.notif_text_stopped))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .addAction(action(R.string.notif_action_start, serviceAction(AirPlayService.ACTION_USER_START, 2, true)))
            .build()
        safeNotify(NOTIF_STOPPED, n)
    }

    fun cancelStopped() = nm.cancel(NOTIF_STOPPED)

    /**
     * Boot prompt for Android 15+, where a media/connectedDevice FGS cannot be started from
     * BOOT_COMPLETED reliably: the user taps once to start the receiver.
     */
    fun notifyBootPrompt() {
        val n = builder(CHANNEL_CONNECT)
            .setSmallIcon(R.drawable.ic_stat_screendock)
            .setContentTitle(ctx.getString(R.string.notif_boot_title))
            .setContentText(ctx.getString(R.string.notif_boot_text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        safeNotify(NOTIF_BOOT, n)
    }

    /**
     * Session/approval alert. Android 10+ blocks background activity starts, so "auto open" is a
     * heads-up notification the user taps. (USE_FULL_SCREEN_INTENT is deliberately not used:
     * Play restricts it to calling/alarm apps.)
     */
    fun notifyAlert(id: Int, title: String, text: String, actions: List<Notification.Action> = emptyList()) {
        val pi = openAppIntent()
        val b = builder(CHANNEL_CONNECT)
            .setSmallIcon(R.drawable.ic_stat_screendock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTimeoutAfter(ALERT_TIMEOUT_MS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION") b.setPriority(Notification.PRIORITY_HIGH)
        }
        actions.forEach { b.addAction(it) }
        safeNotify(id, b.build())
    }

    fun notifySessionStarted(sender: String) = notifyAlert(
        NOTIF_SESSION,
        ctx.getString(R.string.notif_session_started, sender),
        ctx.getString(R.string.notif_session_started_text),
    )

    fun notifyApproval(sender: String) = notifyAlert(
        NOTIF_APPROVAL,
        ctx.getString(R.string.notif_approval_title, sender),
        ctx.getString(R.string.notif_approval_text),
        listOf(
            action(R.string.notif_action_allow, serviceAction(AirPlayService.ACTION_APPROVE, 3)),
            action(R.string.notif_action_deny, serviceAction(AirPlayService.ACTION_DENY, 4)),
        ),
    )

    fun cancelAlerts() { nm.cancel(NOTIF_SESSION); nm.cancel(NOTIF_APPROVAL) }
    fun cancelApproval() = nm.cancel(NOTIF_APPROVAL)

    private fun safeNotify(id: Int, n: Notification) {
        try { nm.notify(id, n) } catch (_: SecurityException) { /* POST_NOTIFICATIONS denied */ }
    }

    companion object {
        const val CHANNEL_RECEIVER = "airplay_receiver"
        const val CHANNEL_CONNECT = "airplay_connect"
        const val NOTIF_FOREGROUND = 1
        const val NOTIF_SESSION = 2
        const val NOTIF_APPROVAL = 3
        const val NOTIF_STOPPED = 4
        const val NOTIF_BOOT = 5
        private const val ALERT_TIMEOUT_MS = 30_000L
    }
}
