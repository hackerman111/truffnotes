package inkspace.text

import inkspace.core.id.ObjectId
import inkspace.core.payload.TextPayload
import inkspace.core.text.ImeEdit

class TextEditSession(
    val objectId: ObjectId,
    initial: TextPayload,
    val focusEpoch: Long,
    private val maxCharacters: Int = 1_000_000
) {
    var payload: TextPayload = initial.copy(runs = java.util.List.copyOf(initial.runs))
        private set
    var selectionStart: Int = initial.text.length
        private set
    var selectionEnd: Int = initial.text.length
        private set
    var composingRange: IntRange? = null
        private set
    private var compositionBase: TextPayload? = null
    private var compositionSelection: Pair<Int, Int>? = null
    var focused: Boolean = true
        private set

    init { require(initial.text.length <= maxCharacters) }

    fun select(start: Int, end: Int): Boolean {
        val map = TextInputMap(payload.text)
        if (!focused || !map.isBoundary(start) || !map.isBoundary(end)) return false
        selectionStart = start
        selectionEnd = end
        return true
    }

    fun replace(epoch: Long, start: Int, end: Int, text: String, composing: Boolean, newCursorPosition: Int = 1): Boolean {
        if (!focused || epoch != focusEpoch || start > end || !TextInputMap.isWellFormed(text)) return false
        val map = TextInputMap(payload.text)
        if (!map.isBoundary(start) || !map.isBoundary(end)) return false
        if (payload.text.length.toLong() - (end - start) + text.length > maxCharacters) return false
        if (composing && compositionBase == null) {
            compositionBase = payload
            compositionSelection = selectionStart to selectionEnd
        }
        val delta = text.length - (end - start)
        val runs = payload.runs.flatMap { run ->
            buildList {
                if (run.start < start) add(run.copy(end = minOf(run.end, start)))
                if (run.end > end) add(run.copy(start = maxOf(run.start, end) + delta, end = run.end + delta))
            }.filter { it.start < it.end }
        }
        val inherited = payload.runs.lastOrNull { it.start <= start && it.end >= start }
        val inserted = if (text.isNotEmpty() && inherited != null) listOf(inherited.copy(start = start, end = start + text.length)) else emptyList()
        payload = payload.copy(text = payload.text.replaceRange(start, end, text), runs = java.util.List.copyOf((runs + inserted).sortedBy { it.start }))
        val nextMap = TextInputMap(payload.text)
        val cursor = if (newCursorPosition > 0) start.toLong() + text.length + newCursorPosition - 1 else start.toLong() + newCursorPosition
        selectionStart = nextMap.floor(cursor.coerceIn(0L, payload.text.length.toLong()).toInt())
        selectionEnd = selectionStart
        composingRange = if (composing) start until start + text.length else null
        if (!composing) finishComposition()
        return true
    }

    fun deleteSurrounding(epoch: Long, before: Int, after: Int): Boolean {
        if (before < 0 || after < 0) return false
        val map = TextInputMap(payload.text)
        val left = minOf(selectionStart, selectionEnd)
        val right = maxOf(selectionStart, selectionEnd)
        return replace(epoch, map.floor((left.toLong() - before).coerceAtLeast(0).toInt()),
            map.ceil((right.toLong() + after).coerceAtMost(payload.text.length.toLong()).toInt()), "", false)
    }

    fun apply(edit: ImeEdit): Boolean {
        if (edit.objectId != objectId || edit.focusEpoch != focusEpoch || !focused) return false
        return when (edit) {
            is ImeEdit.Replace -> replace(edit.focusEpoch, edit.start, edit.end, edit.text, edit.composing, edit.newCursorPosition)
            is ImeEdit.Selection -> select(edit.start, edit.end)
            is ImeEdit.Finish -> { if (edit.cancel) cancelComposition() else finishComposition(); true }
        }
    }
    fun setComposingRegion(epoch: Long, start: Int, end: Int): Boolean {
        val map = TextInputMap(payload.text)
        if (!focused || epoch != focusEpoch || start > end || !map.isBoundary(start) || !map.isBoundary(end)) return false
        if (compositionBase == null) { compositionBase = payload; compositionSelection = selectionStart to selectionEnd }
        composingRange = start until end
        return true
    }
    fun deleteSurroundingCodePoints(epoch: Long, before: Int, after: Int): Boolean {
        if (before < 0 || after < 0) return false
        val text = payload.text
        val left = minOf(selectionStart, selectionEnd)
        val right = maxOf(selectionStart, selectionEnd)
        val leftCount = text.codePointCount(0, left)
        val rightCount = text.codePointCount(right, text.length)
        val map = TextInputMap(text)
        return replace(epoch, map.floor(text.offsetByCodePoints(left, -minOf(before, leftCount))),
            map.ceil(text.offsetByCodePoints(right, minOf(after, rightCount))), "", false)
    }

    fun finishComposition() {
        compositionBase = null
        compositionSelection = null
        composingRange = null
    }
    fun cancelComposition() {
        compositionBase?.let { payload = it }
        compositionSelection?.let { (start, end) -> selectionStart = start; selectionEnd = end }
        finishComposition()
    }
    fun blur() { cancelComposition(); focused = false }
}
