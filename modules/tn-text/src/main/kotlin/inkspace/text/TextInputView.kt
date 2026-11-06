package inkspace.text

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.*
import inkspace.core.payload.TextPayload

/** A native editor node. Composition stays in this focus-bound draft until its logical commit. */
class TextInputView(context: Context) : View(context) {
    private var session: TextEditSession? = null
    var onCommit: (TextPayload) -> Unit = {}
    var onDraft: (TextPayload) -> Unit = {}
    var paintPlan: TextPaintPlan? = null
        set(value) { field=value;invalidate();updateCursorAnchor() }
    private val cursorPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val bound=session ?: return
        val plan=paintPlan ?: return
        if(plan.layout.text.toString()!=bound.payload.text) return
        if(bound.selectionStart!=bound.selectionEnd) {
            val path=android.graphics.Path()
            plan.layout.getSelectionPath(minOf(bound.selectionStart,bound.selectionEnd),maxOf(bound.selectionStart,bound.selectionEnd),path)
            cursorPaint.color=0x553b82f6
            canvas.drawPath(path,cursorPaint)
        } else {
            val cursor=plan.cursorRect(bound.selectionStart)
            cursorPaint.color=0xff2563eb.toInt()
            canvas.drawRect(cursor.left.toFloat(),cursor.top.toFloat(),cursor.right.toFloat(),cursor.bottom.toFloat(),cursorPaint)
        }
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if(!isEnabled || session?.focused!=true) return false
        if(event.action==android.view.MotionEvent.ACTION_DOWN || event.action==android.view.MotionEvent.ACTION_MOVE) {
            val offset=paintPlan?.offsetAt(event.x,event.y.toInt()) ?: return true
            session?.select(offset,offset)
            requestFocus();inputManager.showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);updateDraft()
        }
        return true
    }
    private fun updateCursorAnchor() {
        val bound=session ?: return
        val plan=paintPlan ?: return
        if(plan.layout.text.toString()!=bound.payload.text || bound.selectionStart>plan.layout.text.length) return
        val cursor=plan.cursorRect(bound.selectionStart)
        val matrix=android.graphics.Matrix()
        transformMatrixToGlobal(matrix)
        val info=CursorAnchorInfo.Builder().setMatrix(matrix).setSelectionRange(bound.selectionStart,bound.selectionEnd)
            .setInsertionMarkerLocation(cursor.left.toFloat(),cursor.top.toFloat(),cursor.bottom.toFloat(),cursor.bottom.toFloat(),CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION).build()
        inputManager.updateCursorAnchorInfo(this,info)
    }
    private var batchDepth = 0
    private var changed = false
    private var published: TextPayload? = null
    private val inputManager get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init { isFocusable = true; isFocusableInTouchMode = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun bind(next: TextEditSession) {
        session?.blur()
        session = next
        published = next.payload
        batchDepth = 0
        changed = false
        requestFocus()
        inputManager.restartInput(this)
        inputManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    fun unbind() {
        session?.blur()
        session = null
        batchDepth = 0
        changed = false
        inputManager.hideSoftInputFromWindow(windowToken, 0)
    }
    override fun onCheckIsTextEditor(): Boolean = isEnabled && session?.focused == true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val bound = session?.takeIf { it.focused && isEnabled } ?: return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = bound.selectionStart
        outAttrs.initialSelEnd = bound.selectionEnd
        return object : BaseInputConnection(this, false) {
            private fun active() = session === bound && bound.focused && isEnabled
            private fun edit(text: CharSequence, cursor: Int, composing: Boolean): Boolean {
                if (!active()) return false
                val range = bound.composingRange
                val start = range?.first ?: minOf(bound.selectionStart, bound.selectionEnd)
                val end = range?.let { it.last + 1 } ?: maxOf(bound.selectionStart, bound.selectionEnd)
                val accepted = bound.replace(bound.focusEpoch, start, end, text.toString(), composing, cursor)
                if (accepted) { changed = true; updateDraft(); if (!composing) publish() }
                return accepted
            }
            override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean { if(!active()) return false;updateCursorAnchor();return true }
            override fun beginBatchEdit(): Boolean { if (!active()) return false; batchDepth++; return true }
            override fun endBatchEdit(): Boolean { if (!active()) return false; if (batchDepth > 0) batchDepth--; publish(); return true }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = edit(text, newCursorPosition, true)
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean = edit(text, newCursorPosition, false)
            override fun setComposingRegion(start: Int, end: Int): Boolean {
                if (!active()) return false
                val result = bound.setComposingRegion(bound.focusEpoch, minOf(start, end), maxOf(start, end))
                updateDraft(); return result
            }
            override fun finishComposingText(): Boolean {
                if (!active()) return false
                bound.finishComposition(); updateDraft(); publish(); return true
            }
            override fun setSelection(start: Int, end: Int): Boolean {
                if (!active() || !bound.select(start, end)) return false
                updateDraft(); return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, false)
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, true)
            private fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
                if (!active()) return false
                val result = if (codePoints) bound.deleteSurroundingCodePoints(bound.focusEpoch, before, after)
                    else bound.deleteSurrounding(bound.focusEpoch, before, after)
                if (result) { changed = true; updateDraft(); publish() }
                return result
            }
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                if (!active()) return ""
                val end = minOf(bound.selectionStart, bound.selectionEnd)
                val start = TextInputMap(bound.payload.text).ceil((end.toLong() - n.coerceAtLeast(0)).coerceAtLeast(0).toInt())
                return bound.payload.text.substring(start, end)
            }
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
                if (!active()) return ""
                val start = maxOf(bound.selectionStart, bound.selectionEnd)
                val end = TextInputMap(bound.payload.text).floor((start.toLong() + n.coerceAtLeast(0)).coerceAtMost(bound.payload.text.length.toLong()).toInt())
                return bound.payload.text.substring(start, end)
            }
            override fun getSelectedText(flags: Int): CharSequence = if (!active()) "" else bound.payload.text.substring(minOf(bound.selectionStart, bound.selectionEnd), maxOf(bound.selectionStart, bound.selectionEnd))
            override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText? {
                if (!active()) return null
                return ExtractedText().apply {
                    text = bound.payload.text; startOffset = 0
                    partialStartOffset = -1; partialEndOffset = -1
                    selectionStart = bound.selectionStart; selectionEnd = bound.selectionEnd
                }
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!active()) return false
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> delete(1, 0, true)
                    KeyEvent.KEYCODE_FORWARD_DEL -> delete(0, 1, true)
                    KeyEvent.KEYCODE_ENTER -> edit("\n", 1, false)
                    else -> if (event.unicodeChar > 0) edit(String(Character.toChars(event.unicodeChar)), 1, false) else super.sendKeyEvent(event)
                }
            }
            override fun performContextMenuAction(id: Int): Boolean {
                if (!active()) return false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                return when (id) {
                    android.R.id.selectAll -> setSelection(0, bound.payload.text.length)
                    android.R.id.copy, android.R.id.cut -> {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Text", getSelectedText(0)))
                        if (id == android.R.id.cut) edit("", 1, false) else true
                    }
                    android.R.id.paste -> try {
                        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context) ?: return false
                        edit(text, 1, false)
                    } catch (_: SecurityException) { false }
                    else -> false
                }
            }
        }
    }
    private fun updateDraft() {
        val bound = session ?: return
        onDraft(bound.payload)
        val range = bound.composingRange
        inputManager.updateSelection(this, bound.selectionStart, bound.selectionEnd, range?.first ?: -1, range?.let { it.last + 1 } ?: -1)
        updateCursorAnchor()
        invalidate()
    }
    private fun publish() {
        val bound = session ?: return
        if (!changed || batchDepth > 0 || bound.composingRange != null) return
        changed = false
        if (bound.payload == published) return
        published = bound.payload
        onCommit(bound.payload)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        invalidate()
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = isEnabled
        info.text = session?.payload?.text
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        session?.let { info.setTextSelection(it.selectionStart, it.selectionEnd) }
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val bound = session ?: return super.performAccessibilityAction(action, arguments)
        if (!isEnabled || !bound.focused) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                val text = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE) ?: return false
                val result = bound.replace(bound.focusEpoch, 0, bound.payload.text.length, text.toString(), false)
                if (result) { changed = true; updateDraft(); publish() }
                result
            }
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
                val start = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT) ?: return false
                val end = arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT)
                bound.select(start, end).also { if (it) updateDraft() }
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) { session?.blur(); changed = false; batchDepth = 0; session?.let { onDraft(it.payload) } }
    }
    override fun onDetachedFromWindow() { unbind(); super.onDetachedFromWindow() }
}
