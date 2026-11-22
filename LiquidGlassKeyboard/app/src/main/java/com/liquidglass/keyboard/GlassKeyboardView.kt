package com.liquidglass.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.max
import kotlin.math.min

/**
 * Клавиатура, нарисованная вручную на Canvas в стиле «liquid glass»:
 * полупрозрачные клавиши с градиентом, бликом по верхней кромке, переливающейся рамкой
 * и «жидким» световым пятном, которое растекается от точки касания.
 */
class GlassKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSwitchIme()
        fun onLanguageChanged(lang: String)
    }

    enum class Shift { OFF, ON, CAPS }

    private enum class Layout { RU, EN, SYMBOLS }
    private enum class Kind { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, MODE, LANG, GAP }

    private class Key(
        val label: String,
        val kind: Kind,
        val weight: Float = 1f,
        val alt: String? = null
    ) {
        val rect = RectF()
        val hit = RectF()
        var level = 0f
        var pressed = false
        var touchX = 0f
        var touchY = 0f
        var body: Shader? = null
        var border: Shader? = null
    }

    var listener: Listener? = null

    var shift: Shift = Shift.OFF
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    // ---- размеры ----
    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val keyH = 52f * dp
    private val gap = 4.5f * dp
    private val rowGap = 7f * dp
    private val sidePad = 4f * dp
    private val topPad = 10f * dp
    private val botPad = 8f * dp
    private val radius = 13f * dp
    private var bottomInset = 0

    // ---- состояние раскладки ----
    private var letters = Layout.EN
    private var layout = Layout.EN
    private var keyRows: List<List<Key>> = emptyList()

    // ---- краски ----
    private val panelPaint = Paint()
    private val sheenPaint = Paint()
    private val edgePaint = Paint()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(3f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
    }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f * dp
    }
    private val keyPath = Path()
    private val iconPath = Path()
    private val tmp = RectF()

    // ---- тема ----
    private var dark = isNight()
    private var textColor = 0
    private var hintColor = 0

    // ---- касания ----
    private val handler = Handler(Looper.getMainLooper())
    private val active = HashMap<Int, Key>()
    private val timers = HashMap<Int, Runnable>()
    private val fired = HashSet<Int>()
    private var lastShiftTap = 0L

    // ------------------------------------------------------------------ public API

    fun setLanguage(lang: String) {
        letters = if (lang == "ru") Layout.RU else Layout.EN
        if (layout != Layout.SYMBOLS) layout = letters
        rebuild()
    }

    fun showSymbols() {
        layout = Layout.SYMBOLS
        rebuild()
    }

    fun showLetters() {
        layout = letters
        rebuild()
    }

    // ------------------------------------------------------------------ тема и размеры

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun white(a: Int) = Color.argb(a, 255, 255, 255)

    /** Подсветка: белая в тёмной теме, голубоватая в светлой. */
    private fun tint(a: Int) = if (dark) white(a) else Color.argb(a, 90, 130, 255)

    private fun applyTheme() {
        dark = isNight()
        textColor = if (dark) white(245) else Color.argb(255, 24, 28, 44)
        hintColor = (textColor and 0x00FFFFFF) or (0x80 shl 24)
        if (dark) {
            textPaint.setShadowLayer(2.5f * dp, 0f, 1f * dp, Color.argb(80, 0, 0, 0))
        } else {
            textPaint.clearShadowLayer()
        }
        buildPanel()
        keyRows.forEach { row -> row.forEach { buildShaders(it) } }
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val b = if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            insets.systemWindowInsetBottom
        }
        if (b != bottomInset) {
            bottomInset = b
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (topPad + 4 * keyH + 3 * rowGap + botPad).toInt() + bottomInset
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildPanel()
        rebuild()
    }

    override fun onDetachedFromWindow() {
        cancelAllTouches()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ раскладки

    private fun chars(s: String, alts: String? = null): List<Key> =
        s.mapIndexed { i, ch ->
            Key(
                ch.toString(),
                Kind.CHAR,
                1f,
                alts?.getOrNull(i)?.toString()?.takeIf { it != "_" }
            )
        }

    private fun buildRows(): List<List<Key>> {
        val symbols2 = if (letters == Layout.RU) "@#₽_&-+()/" else "@#\$_&-+()/"
        val top: List<List<Key>> = when (layout) {
            Layout.RU -> listOf(
                chars("йцукенгшщзхъ", "1234ё67890__"),
                chars("фывапролджэ"),
                listOf(Key("", Kind.SHIFT, 1.5f)) + chars("ячсмитьбю") + Key("", Kind.BACKSPACE, 1.5f)
            )
            Layout.EN -> listOf(
                chars("qwertyuiop", "1234567890"),
                listOf(Key("", Kind.GAP, 0.5f)) + chars("asdfghjkl") + Key("", Kind.GAP, 0.5f),
                listOf(Key("", Kind.SHIFT, 1.5f)) + chars("zxcvbnm") + Key("", Kind.BACKSPACE, 1.5f)
            )
            Layout.SYMBOLS -> listOf(
                chars("1234567890"),
                chars(symbols2),
                listOf(Key("", Kind.GAP, 1.5f)) + chars("*\"':;!?") + Key("", Kind.BACKSPACE, 1.5f)
            )
        }
        val maxW = top.maxOf { row -> row.fold(0f) { a, k -> a + k.weight } }
        val fixed = 1.5f + 1.2f + 1f + 1f + 1.8f
        val modeLabel = if (layout == Layout.SYMBOLS) {
            if (letters == Layout.RU) "АБВ" else "ABC"
        } else {
            "?123"
        }
        val bottom = listOf(
            Key(modeLabel, Kind.MODE, 1.5f),
            Key("", Kind.LANG, 1.2f),
            Key(",", Kind.CHAR, 1f),
            Key("", Kind.SPACE, maxW - fixed),
            Key(".", Kind.CHAR, 1f),
            Key("", Kind.ENTER, 1.8f)
        )
        return top + listOf(bottom)
    }

    private fun rebuild() {
        cancelAllTouches()
        keyRows = if (width > 0) buildRows() else emptyList()
        layoutKeys()
        invalidate()
    }

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f || keyRows.isEmpty()) return
        val maxW = keyRows.maxOf { row -> row.fold(0f) { a, k -> a + k.weight } }
        val u = (w - 2 * sidePad - (maxW - 1) * gap) / maxW

        keyRows.forEachIndexed { ri, row ->
            val rowW = row.fold(0f) { a, k -> a + k.weight * u + (k.weight - 1) * gap } + (row.size - 1) * gap
            var x = (w - rowW) / 2f
            val y = topPad + ri * (keyH + rowGap)
            val firstReal = row.indexOfFirst { it.kind != Kind.GAP }
            val lastReal = row.indexOfLast { it.kind != Kind.GAP }
            row.forEachIndexed { ci, k ->
                val kw = k.weight * u + (k.weight - 1) * gap
                k.rect.set(x, y, x + kw, y + keyH)
                k.hit.set(x - gap / 2, y - rowGap / 2, x + kw + gap / 2, y + keyH + rowGap / 2)
                if (ci == firstReal) k.hit.left = 0f
                if (ci == lastReal) k.hit.right = w
                if (ri == 0) k.hit.top = 0f
                if (ri == keyRows.lastIndex) k.hit.bottom = height.toFloat()
                x += kw + gap
                buildShaders(k)
            }
        }
    }

    // ------------------------------------------------------------------ шейдеры «стекла»

    private fun buildPanel() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val (t, b) = if (dark) {
            Pair(Color.argb(105, 18, 20, 36), Color.argb(160, 10, 12, 24))
        } else {
            Pair(Color.argb(140, 238, 242, 255), Color.argb(185, 222, 228, 246))
        }
        panelPaint.shader = LinearGradient(0f, 0f, 0f, h, t, b, Shader.TileMode.CLAMP)
        sheenPaint.shader = RadialGradient(
            w * 0.15f, 0f, w * 0.95f,
            white(if (dark) 36 else 95), white(0), Shader.TileMode.CLAMP
        )
        edgePaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(white(0), white(if (dark) 190 else 255), white(0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun buildShaders(k: Key) {
        val r = k.rect
        val special = k.kind != Kind.CHAR && k.kind != Kind.SPACE
        val (top, bottom) = when {
            k.kind == Kind.ENTER -> Pair(Color.argb(235, 110, 175, 255), Color.argb(205, 62, 120, 250))
            dark && special -> Pair(white(46), white(16))
            dark -> Pair(white(78), white(28))
            special -> Pair(white(150), white(80))
            else -> Pair(white(215), white(120))
        }
        k.body = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)

        val colors = if (dark) {
            intArrayOf(white(210), white(30), white(120))
        } else {
            intArrayOf(white(255), Color.argb(46, 100, 110, 150), white(190))
        }
        k.border = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            colors, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ отрисовка

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, panelPaint)
        c.drawRect(0f, 0f, w, h, sheenPaint)
        c.drawRect(0f, 0f, w, 1.5f * dp, edgePaint)

        var animating = false
        for (row in keyRows) {
            for (k in row) {
                if (k.kind == Kind.GAP) continue
                if (k.pressed && k.level < 1f) {
                    k.level = min(1f, k.level + 0.28f)
                    animating = true
                } else if (!k.pressed && k.level > 0f) {
                    k.level = max(0f, k.level - 0.07f)
                    animating = true
                }
                drawKey(c, k)
            }
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawKey(c: Canvas, k: Key) {
        val r = k.rect
        val lv = k.level
        val cx = r.centerX()
        val cy = r.centerY()
        keyPath.reset()
        keyPath.addRoundRect(r, radius, radius, Path.Direction.CW)

        c.save()
        val s = 1f - 0.05f * lv
        c.scale(s, s, cx, cy)

        // мягкая тень под стеклом (внутрь клавиши не заходит)
        c.save()
        c.clipOutPath(keyPath)
        shadowPaint.color = Color.argb(if (dark) 90 else 46, 0, 0, 0)
        c.drawRoundRect(r.left, r.top + 2.5f * dp, r.right, r.bottom + 2.5f * dp, radius, radius, shadowPaint)
        c.restore()

        // тело стекла
        bodyPaint.shader = k.body
        c.drawRoundRect(r, radius, radius, bodyPaint)

        if (k.kind == Kind.SHIFT && shift != Shift.OFF) {
            overlayPaint.color = white(if (dark) 70 else 150)
            c.drawRoundRect(r, radius, radius, overlayPaint)
        }

        // «жидкое» пятно света от точки касания
        if (lv > 0.01f) {
            c.save()
            c.clipPath(keyPath)
            val gr = max(r.width(), r.height()) * (0.6f + 0.9f * lv)
            glowPaint.shader = RadialGradient(
                k.touchX, k.touchY, gr,
                intArrayOf(tint((170 * lv).toInt()), tint((70 * lv).toInt()), tint(0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(r, glowPaint)
            c.restore()
        }

        // переливающаяся рамка и блик по верхней кромке
        tmp.set(r)
        tmp.inset(0.6f * dp, 0.6f * dp)
        borderPaint.shader = k.border
        c.drawRoundRect(tmp, radius, radius, borderPaint)

        val hlInset = min(radius, r.width() * 0.28f)
        hlPaint.color = white(if (dark) 105 else 200)
        c.drawLine(r.left + hlInset, r.top + 2f * dp, r.right - hlInset, r.top + 2f * dp, hlPaint)

        drawContent(c, k, cx, cy)
        c.restore()
    }

    private fun drawText(
        c: Canvas, text: String, x: Float, cy: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        val fm = textPaint.fontMetrics
        c.drawText(text, x, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun cased(label: String): String =
        if (shift != Shift.OFF && layout != Layout.SYMBOLS && label[0].isLetter()) label.uppercase() else label

    private fun drawContent(c: Canvas, k: Key, cx: Float, cy: Float) {
        val color = if (k.kind == Kind.ENTER) Color.WHITE else textColor
        val d = dp
        when (k.kind) {
            Kind.CHAR -> {
                drawText(c, cased(k.label), cx, cy, sp(22f), color)
                val a = k.alt
                if (a != null) {
                    drawText(c, a, k.rect.right - 5f * d, k.rect.top + 11f * d, sp(10f), hintColor, Paint.Align.RIGHT)
                }
            }

            Kind.SPACE -> {
                if (layout != Layout.SYMBOLS) {
                    drawText(c, if (letters == Layout.RU) "Русский" else "English", cx, cy, sp(13f), hintColor)
                }
            }

            Kind.MODE -> drawText(c, k.label, cx, cy, sp(15f), color)

            Kind.SHIFT -> {
                iconPath.reset()
                iconPath.moveTo(cx, cy - 9f * d)
                iconPath.lineTo(cx - 9.5f * d, cy + 0.5f * d)
                iconPath.lineTo(cx - 4.5f * d, cy + 0.5f * d)
                iconPath.lineTo(cx - 4.5f * d, cy + 8.5f * d)
                iconPath.lineTo(cx + 4.5f * d, cy + 8.5f * d)
                iconPath.lineTo(cx + 4.5f * d, cy + 0.5f * d)
                iconPath.lineTo(cx + 9.5f * d, cy + 0.5f * d)
                iconPath.close()
                iconPaint.color = color
                iconPaint.style = if (shift != Shift.OFF) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                c.drawPath(iconPath, iconPaint)
                if (shift == Shift.CAPS) {
                    iconPaint.style = Paint.Style.STROKE
                    c.drawLine(cx - 4.5f * d, cy + 12f * d, cx + 4.5f * d, cy + 12f * d, iconPaint)
                }
            }

            Kind.BACKSPACE -> {
                iconPath.reset()
                iconPath.moveTo(cx - 11f * d, cy)
                iconPath.lineTo(cx - 5f * d, cy - 8f * d)
                iconPath.lineTo(cx + 11f * d, cy - 8f * d)
                iconPath.lineTo(cx + 11f * d, cy + 8f * d)
                iconPath.lineTo(cx - 5f * d, cy + 8f * d)
                iconPath.close()
                iconPaint.color = color
                iconPaint.style = Paint.Style.STROKE
                c.drawPath(iconPath, iconPaint)
                c.drawLine(cx - 2f * d, cy - 3.5f * d, cx + 5.5f * d, cy + 3.5f * d, iconPaint)
                c.drawLine(cx + 5.5f * d, cy - 3.5f * d, cx - 2f * d, cy + 3.5f * d, iconPaint)
            }

            Kind.ENTER -> {
                iconPath.reset()
                iconPath.moveTo(cx + 8f * d, cy - 7f * d)
                iconPath.lineTo(cx + 8f * d, cy + 1f * d)
                iconPath.lineTo(cx - 8f * d, cy + 1f * d)
                iconPath.moveTo(cx - 3f * d, cy - 4f * d)
                iconPath.lineTo(cx - 8f * d, cy + 1f * d)
                iconPath.lineTo(cx - 3f * d, cy + 6f * d)
                iconPaint.color = color
                iconPaint.style = Paint.Style.STROKE
                c.drawPath(iconPath, iconPaint)
            }

            Kind.LANG -> {
                iconPaint.color = color
                iconPaint.style = Paint.Style.STROKE
                iconPaint.strokeWidth = 1.5f * d
                c.drawCircle(cx, cy, 8.5f * d, iconPaint)
                tmp.set(cx - 3.6f * d, cy - 8.5f * d, cx + 3.6f * d, cy + 8.5f * d)
                c.drawOval(tmp, iconPaint)
                c.drawLine(cx - 8.5f * d, cy, cx + 8.5f * d, cy, iconPaint)
                iconPaint.strokeWidth = 1.9f * d
            }

            Kind.GAP -> {}
        }
    }

    // ------------------------------------------------------------------ касания

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = keyAt(e.getX(i), e.getY(i))
                if (k != null) startPress(e.getPointerId(i), k, e.getX(i), e.getY(i))
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    move(e.getPointerId(i), e.getX(i), e.getY(i))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(e.getPointerId(e.actionIndex), false)
            }

            MotionEvent.ACTION_CANCEL -> {
                active.keys.toList().forEach { release(it, true) }
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in keyRows) {
            for (k in row) {
                if (k.kind != Kind.GAP && k.hit.contains(x, y)) return k
            }
        }
        return null
    }

    private fun haptic(strong: Boolean = false) {
        performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
        )
    }

    private fun startPress(id: Int, k: Key, x: Float, y: Float) {
        k.pressed = true
        k.touchX = x
        k.touchY = y
        active[id] = k
        fired.remove(id)
        haptic()
        when (k.kind) {
            Kind.BACKSPACE -> {
                listener?.onBackspace()
                val repeat = object : Runnable {
                    override fun run() {
                        listener?.onBackspace()
                        haptic()
                        handler.postDelayed(this, 45)
                    }
                }
                timers[id] = repeat
                handler.postDelayed(repeat, 420)
            }

            Kind.LANG -> schedule(id, 450) { listener?.onSwitchIme() }

            Kind.CHAR -> if (k.alt != null) schedule(id, 380) { commitAlt(k) }

            else -> {}
        }
        invalidate()
    }

    private fun schedule(id: Int, delay: Long, action: () -> Unit) {
        val r = Runnable {
            fired.add(id)
            timers.remove(id)
            haptic(true)
            action()
        }
        timers[id] = r
        handler.postDelayed(r, delay)
    }

    private fun cancelTimer(id: Int) {
        timers.remove(id)?.let { handler.removeCallbacks(it) }
    }

    private fun cancelAllTouches() {
        active.values.forEach { it.pressed = false }
        active.clear()
        timers.values.forEach { handler.removeCallbacks(it) }
        timers.clear()
        fired.clear()
    }

    private fun move(id: Int, x: Float, y: Float) {
        val old = active[id] ?: return
        old.touchX = x
        old.touchY = y
        val nk = keyAt(x, y)
        if (nk !== old) {
            cancelTimer(id)
            old.pressed = false
            active.remove(id)
            fired.remove(id)
            if (nk != null && nk.kind != Kind.BACKSPACE) startPress(id, nk, x, y)
        }
        invalidate()
    }

    private fun release(id: Int, cancel: Boolean) {
        val k = active.remove(id) ?: return
        cancelTimer(id)
        k.pressed = false
        val wasFired = fired.remove(id)
        invalidate()
        // Backspace срабатывает сразу при нажатии, остальные клавиши — при отпускании
        if (!cancel && !wasFired && k.kind != Kind.BACKSPACE) commit(k)
    }

    private fun commit(k: Key) {
        when (k.kind) {
            Kind.CHAR -> {
                val t = cased(k.label)
                if (shift == Shift.ON && k.label[0].isLetter()) shift = Shift.OFF
                listener?.onText(t)
            }

            Kind.SPACE -> listener?.onText(" ")
            Kind.ENTER -> listener?.onEnter()
            Kind.SHIFT -> toggleShift()

            Kind.MODE -> {
                layout = if (layout == Layout.SYMBOLS) letters else Layout.SYMBOLS
                rebuild()
            }

            Kind.LANG -> {
                letters = if (letters == Layout.RU) Layout.EN else Layout.RU
                if (layout != Layout.SYMBOLS) layout = letters
                rebuild()
                listener?.onLanguageChanged(if (letters == Layout.RU) "ru" else "en")
            }

            else -> {}
        }
    }

    private fun commitAlt(k: Key) {
        val a = k.alt ?: return
        val t = if (shift != Shift.OFF) a.uppercase() else a
        if (shift == Shift.ON && a[0].isLetter()) shift = Shift.OFF
        listener?.onText(t)
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.CAPS -> Shift.OFF
            shift == Shift.ON && now - lastShiftTap < 350 -> Shift.CAPS
            shift == Shift.ON -> Shift.OFF
            else -> Shift.ON
        }
        lastShiftTap = now
    }

    init {
        applyTheme()
    }
}
