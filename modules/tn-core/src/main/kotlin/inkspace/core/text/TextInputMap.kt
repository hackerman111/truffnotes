package inkspace.core.text

import java.util.regex.Pattern

/** Offsets in payload/runs and Android IME are UTF-16; encoded byte offsets are UTF-8. */
class TextInputMap(val text: String) {
    private val boundaries: IntArray
    private val bytesAtBoundary: IntArray
    init {
        require(text.length <= 1_000_000 && isWellFormed(text)) { "Text input map size limit" }
        val starts = IntArray(text.length + 1)
        val matcher = GRAPHEME.matcher(text)
        var count = 1
        while (matcher.find()) starts[count++] = matcher.end()
        boundaries = starts.copyOf(count)
        bytesAtBoundary = IntArray(count)
        var chars = 0
        var bytes = 0
        var boundary = 1
        while (chars < text.length) {
            val codePoint = text.codePointAt(chars)
            chars += Character.charCount(codePoint)
            bytes += when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
            if (boundary < count && chars == boundaries[boundary]) bytesAtBoundary[boundary++] = bytes
        }
    }
    fun isBoundary(offset: Int): Boolean = boundaries.binarySearch(offset) >= 0
    fun floor(offset: Int): Int {
        val found = boundaries.binarySearch(offset.coerceIn(0, text.length))
        return boundaries[if (found >= 0) found else -found - 2]
    }
    fun ceil(offset: Int): Int {
        val found = boundaries.binarySearch(offset.coerceIn(0, text.length))
        return boundaries[if (found >= 0) found else -found - 1]
    }
    fun utf8Offset(utf16: Int): Int {
        val found = boundaries.binarySearch(utf16)
        require(found >= 0) { "Offset splits a grapheme" }
        return bytesAtBoundary[found]
    }
    fun utf16Offset(utf8: Int): Int {
        val found = bytesAtBoundary.binarySearch(utf8)
        require(found >= 0) { "Offset splits UTF-8 or a grapheme" }
        return boundaries[found]
    }
    companion object {
        private val GRAPHEME = Pattern.compile("\\X")
        fun isWellFormed(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (Character.isHighSurrogate(char)) {
                    if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
                } else if (Character.isLowSurrogate(char)) return false
            }
            return true
        }
    }
}
