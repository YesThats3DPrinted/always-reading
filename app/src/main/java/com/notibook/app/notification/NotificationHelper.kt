package com.notibook.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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

    /** Characters that are EPUB formatting artefacts when they lead a sentence. */
    private val LEADING_STRIP_CHARS = charArrayOf(
        '—', '–', '‒', '―',   // em dash, en dash, figure dash, horizontal bar
        '\u200B', '\u200C', '\u200D', '\uFEFF'  // zero-width / BOM
    )

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
            append("$pct%")
            append(" · ${book.title}")
            if (sentence.chapter.isNotBlank()) append(" · ${sentence.chapter}")
        }

        // Strip EPUB formatting artefacts (leading dashes etc.) for display only.
        // The original text is preserved in the database.
        val displayText = sentence.text.trimStart(*LEADING_STRIP_CHARS).trim()

        val expanded = buildExpandedView(context, book.id, titleText, displayText, isFirst, isLast)

        return NotificationCompat.Builder(context, NotiBookApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_book)
            .setContentText(displayText)
            .setCustomBigContentView(expanded)
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
        displayText: String,
        isFirst: Boolean,
        isLast: Boolean
    ): RemoteViews = RemoteViews(context.packageName, R.layout.notification_expanded).apply {
        val isDark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val textColor      = if (isDark) Color.WHITE        else Color.BLACK
        val textColorFaint = if (isDark) 0xAAFFFFFF.toInt() else 0xAA000000.toInt()
        // ImageButton alpha: 255 = fully opaque, 100 = dimmed for disabled state
        val iconAlpha      = 255
        val iconAlphaFaint = 100

        setTextViewText(R.id.tv_notification_title, titleText)
        setTextViewText(R.id.tv_sentence, displayText)
        setInt(R.id.tv_notification_title, "setTextColor", textColor)
        setInt(R.id.tv_sentence,           "setTextColor", textColor)

        // Tint the arrow SVGs to match the theme text color
        setInt(R.id.btn_prev, "setColorFilter", textColor)
        setInt(R.id.btn_next, "setColorFilter", textColor)

        setOnClickPendingIntent(R.id.tv_notification_title, makeOpenAppIntent(context, bookId))

        wireNavigation(context, bookId, isFirst, isLast, iconAlpha, iconAlphaFaint, textColorFaint)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun RemoteViews.wireNavigation(
        context: Context,
        bookId: Long,
        isFirst: Boolean,
        isLast: Boolean,
        iconAlpha: Int,
        iconAlphaFaint: Int,
        textColorFaint: Int
    ) {
        // Both arrow buttons always visible — dim via alpha when at a boundary
        // so both grey pills render equally regardless of position in the book.
        if (isFirst) {
            setInt(R.id.btn_prev, "setImageAlpha", iconAlphaFaint)
        } else {
            setInt(R.id.btn_prev, "setImageAlpha", iconAlpha)
            setOnClickPendingIntent(
                R.id.btn_prev,
                makeBroadcast(context, NotificationActionReceiver.ACTION_PREV, bookId)
            )
        }
        if (isLast) {
            setInt(R.id.btn_next, "setImageAlpha", iconAlphaFaint)
        } else {
            setInt(R.id.btn_next, "setImageAlpha", iconAlpha)
            setOnClickPendingIntent(
                R.id.btn_next,
                makeBroadcast(context, NotificationActionReceiver.ACTION_NEXT, bookId)
            )
        }
    }

    private fun makeOpenAppIntent(context: Context, bookId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationActionReceiver.EXTRA_BOOK_ID, bookId)
        }
        return PendingIntent.getActivity(
            context, bookId.toInt(), intent,
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
