package com.alwaysreading.app.epub

/**
 * Represents a single spine item (chapter/section) from an EPUB's OPF manifest.
 *
 * @param index        0-based position in the spine order
 * @param href         relative href from the OPF manifest (e.g. "Text/chapter01.xhtml")
 * @param absolutePath full filesystem path to the HTML file in the extracted cache dir
 * @param chapterTitle title extracted from the HTML file's h1/h2/h3 or <title> tag
 */
data class SpineItem(
    val index: Int,
    val href: String,
    val absolutePath: String,
    val chapterTitle: String
)
