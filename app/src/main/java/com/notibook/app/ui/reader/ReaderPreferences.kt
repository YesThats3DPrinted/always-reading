package com.notibook.app.ui.reader

import android.content.Context

/** Persists reader settings across sessions. Only font size is user-configurable. */
class ReaderPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    companion object {
        private const val KEY_FONT_SIZE  = "reader_font_size"
        const val DEFAULT_FONT_SIZE      = 20
        val FONT_SIZE_RANGE              = 12..28 step 2
    }
}
