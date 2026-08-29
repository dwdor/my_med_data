package com.medvault.app.ocr

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal `.docx` text extraction: a `.docx` is a zip archive, and its body text lives in
 * `word/document.xml` as a sequence of `<w:t>` runs. This intentionally avoids a heavy dependency
 * like Apache POI (not well-suited to Android) — it only pulls plain text for indexing/search
 * (see SPEC.md §6.4); the original file stays available via "Teilen" for full-fidelity viewing.
 */
object DocxTextExtractor {

    fun extract(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return extractTextFromXml(zip)
                }
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private fun extractTextFromXml(input: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val sb = StringBuilder()
        var inTextRun = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> if (isTextRunTag(parser.name)) inTextRun = true
                XmlPullParser.TEXT -> if (inTextRun) sb.append(parser.text)
                XmlPullParser.END_TAG -> if (isTextRunTag(parser.name)) {
                    inTextRun = false
                    sb.append(' ')
                } else if (parser.name == "w:p" || parser.name == "p") {
                    sb.append('\n')
                }
            }
            eventType = parser.next()
        }
        return sb.toString().trim()
    }

    private fun isTextRunTag(name: String) = name == "w:t" || name == "t"
}
