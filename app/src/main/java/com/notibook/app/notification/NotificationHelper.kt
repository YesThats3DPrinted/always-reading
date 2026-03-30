package com.notibook.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.notibook.app.MainActivity
import com.notibook.app.NotiBookApp
import com.notibook.app.R
import com.notibook.app.data.db.BookEntity
import com.notibook.app.data.db.SentenceEntity

object NotificationHelper {

    fun notificationId(bookId: Long): Int = bookId.toInt()

    private val PURPLE     = Color.parseColor("#6650A4")
    private val WHITE_FULL = Color.WHITE
    private val WHITE_DIM  = Color.argb(80, 255, 255, 255)

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Posts (or updates) the notification for [book] at [sentence].
     * Safe to call from any thread/context — no foreground service required.
     */
    fun show(context: Context, book: BookEntity, sentence: SentenceEntity) {
        if (book.isParsing) return
        val notification = buildNotification(context, book, sentence)
        try {
            NotificationManagerCompat.from(context).notify(notificationId(book.id), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently ignore
        }
    }

    /** Cancels the notification for [bookId]. */
    fun hide(context: Context, bookId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(bookId))
    }

    // ── Notification builder ─────────────────────────────────────────────────

    private fun buildNotification(
        context: Context,
        book: BookEntity,
        sentence: SentenceEntity
    ): Notification {
        val isFirst = sentence.sentenceIndex == 0
        val isLast  = book.totalSentences > 0 && sentence.sentenceIndex >= book.totalSentences - 1
        val pct     = if (book.totalSentences > 0)
            (sentence.sentenceIndex * 100) / book.totalSentences else 0

        val titleText = buildString {
            append(book.title)
            if (sentence.chapter.isNotBlank()) append(" · ${sentence.chapter}")
            append(" · $pct%")
        }

        val collapsed = buildCollapsedView(context, book.id, titleText, sentence.text, isFirst, isLast)
        val expanded  = buildExpandedView (context, book.id, titleText, sentence.text, isFirst, isLast)

        return NotificationCompat.Builder(context, NotiBookApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_book)
            .setColor(PURPLE)
            .setColorized(true)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            // No setContentIntent — only explicit per-view clicks open the app,
            // so mis-tapping near an arrow never accidentally launches the activity.
            .setDeleteIntent(makeBroadcast(context, NotificationActionReceiver.ACTION_DISMISS, book.id))
            .setOngoing(false)   // regular notification — swipeable; setDeleteIntent fires on swipe
            .setAutoCancel(false)
            .setShowWhen(false)
            .build()
    }

    // ── RemoteViews builders ─────────────────────────────────────────────────

    private fun buildCollapsedView(
        context: Context,
        bookId: Long,
        titleText: String,
        sentenceText: String,
        isFirst: Boolean,
        isLast: Boolean
    ): RemoteViews = RemoteViews(context.packageName, R.layout.notification_collapsed).apply {
        setInt(R.id.notif_root_collapsed, "setBackgroundColor", PURPLE)
        setTextViewText(R.id.tv_notification_title, titleText)
        setTextViewText(R.id.tv_sentence, sentenceText)
        setOnClickPendingIntent(R.id.tv_notification_title, makeOpenAppIntent(context))
        wireNavigation(context, bookId, isFirst, isLast)
    }

    private fun buildExpandedView(
        context: Context,
        bookId: Long,
        titleText: String,
        sentenceText: String,
        isFirst: Boolean,
        isLast: Boolean
    ): RemoteViews = RemoteViews(context.packageName, R.layout.notification_expanded).apply {
        setInt(R.id.notif_root_expanded, "setBackgroundColor", PURPLE)
        setTextViewText(R.id.tv_notification_title, titleText)
        setTextViewText(R.id.tv_sentence, sentenceText)
        setOnClickPendingIntent(R.id.tv_notification_title, makeOpenAppIntent(context))
        wireNavigation(context, bookId, isFirst, isLast)
    }

    private fun RemoteViews.wireNavigation(
        context: Context,
        bookId: Long,
        isFirst: Boolean,
        isLast: Boolean
    ) {
        if (isFirst) {
            setInt(R.id.btn_prev, "setTextColor", WHITE_DIM)
        } else {
            setInt(R.id.btn_prev, "setTextColor", WHITE_FULL)
            setOnClickPendingIntent(
                R.id.btn_prev,
                makeBroadcast(context, NotificationActionReceiver.ACTION_PREV, bookId)
            )
        }
        if (isLast) {
            setInt(R.id.btn_next, "setTextColor", WHITE_DIM)
        } else {
            setInt(R.id.btn_next, "setTextColor", WHITE_FULL)
            setOnClickPendingIntent(
                R.id.btn_next,
                makeBroadcast(context, NotificationActionReceiver.ACTION_NEXT, bookId)
            )
        }
    }

    /** Launches the app's main screen. */
    private fun makeOpenAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun makeBroadcast(context: Context, action: String, bookId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_BOOK_ID, bookId)
        }
        val requestCode = (bookId * 100 + action.hashCode()).toInt()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
