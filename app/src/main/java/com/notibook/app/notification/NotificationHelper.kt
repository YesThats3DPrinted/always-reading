package com.notibook.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
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

    // ── Public entry points ──────────────────────────────────────────────────

    fun show(context: Context, book: BookEntity, sentence: SentenceEntity) {
        if (book.isParsing) return
        val notification = buildNotification(context, book, sentence)
        try {
            NotificationManagerCompat.from(context).notify(notificationId(book.id), notification)
        } catch (_: SecurityException) { }
    }

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

        // Only a custom expanded (big) view — the collapsed state uses Android's
        // standard template, which handles light/dark mode text colors automatically.
        val expanded = buildExpandedView(context, book.id, titleText, sentence.text, isFirst, isLast)

        return NotificationCompat.Builder(context, NotiBookApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_book)
            // Standard collapsed view fields — used by Android's native template.
            .setContentTitle(titleText)
            .setContentText(sentence.text)
            // Custom expanded view — shown when the user swipes down.
            .setCustomBigContentView(expanded)
            // No setContentIntent — only explicit per-view clicks open the app.
            .setDeleteIntent(makeBroadcast(context, NotificationActionReceiver.ACTION_DISMISS, book.id))
            .setOngoing(false)
            .setAutoCancel(false)
            .setShowWhen(false)
            .build()
    }

    // ── RemoteViews builder ──────────────────────────────────────────────────

    private fun buildExpandedView(
        context: Context,
        bookId: Long,
        titleText: String,
        sentenceText: String,
        isFirst: Boolean,
        isLast: Boolean
    ): RemoteViews = RemoteViews(context.packageName, R.layout.notification_expanded).apply {
        // Detect dark mode and set text colors accordingly.
        // Custom RemoteViews don't inherit system notification text colors automatically.
        val isDark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val textColor      = if (isDark) Color.WHITE          else Color.BLACK
        val textColorFaint = if (isDark) 0xAAFFFFFF.toInt()   else 0xAA000000.toInt()

        setTextViewText(R.id.tv_notification_title, titleText)
        setTextViewText(R.id.tv_sentence, sentenceText)
        setInt(R.id.tv_notification_title, "setTextColor", textColor)
        setInt(R.id.tv_sentence,           "setTextColor", textColor)

        setOnClickPendingIntent(R.id.tv_notification_title, makeOpenAppIntent(context))

        wireNavigation(context, bookId, isFirst, isLast, textColor, textColorFaint)
    }

    private fun RemoteViews.wireNavigation(
        context: Context,
        bookId: Long,
        isFirst: Boolean,
        isLast: Boolean,
        textColor: Int,
        textColorFaint: Int
    ) {
        if (isFirst) {
            setViewVisibility(R.id.btn_prev, View.INVISIBLE)
        } else {
            setViewVisibility(R.id.btn_prev, View.VISIBLE)
            setInt(R.id.btn_prev, "setTextColor", textColor)
            setOnClickPendingIntent(
                R.id.btn_prev,
                makeBroadcast(context, NotificationActionReceiver.ACTION_PREV, bookId)
            )
        }
        if (isLast) {
            setViewVisibility(R.id.btn_next, View.INVISIBLE)
        } else {
            setViewVisibility(R.id.btn_next, View.VISIBLE)
            setInt(R.id.btn_next, "setTextColor", textColor)
            setOnClickPendingIntent(
                R.id.btn_next,
                makeBroadcast(context, NotificationActionReceiver.ACTION_NEXT, bookId)
            )
        }
    }

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
