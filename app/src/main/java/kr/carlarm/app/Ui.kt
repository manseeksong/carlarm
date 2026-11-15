package kr.carlarm.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 0.3 화면의 공통 부품(docs/화면_옮김_기록.md의 값). 색과 글자 크기, 판, 버튼, 스위치, 고리, 선 아이콘, 분 고르는 칸, 차례 점.
 * 크기는 dp로 적고(앱 화면 1680×1208, 160dpi에서 1dp = 1px), 글자는 dp로 고정해 글자 크기 설정에 짜임이 깨지지 않게 합니다.
 */
object Ui {
    const val BG = 0xFF131417.toInt()
    const val PANEL = 0xFF1D1E21.toInt()
    const val TRACK = 0xFF313236.toInt()
    const val PRIMARY = 0xFFE0E1E6.toInt()
    const val PRIMARY_DOWN = 0xFFC8C9CF.toInt()
    const val TONAL_DOWN = 0xFF3E3F44.toInt()
    const val ON_PRIMARY = 0xFF131417.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val W84 = 0xD6FFFFFF.toInt()
    const val W64 = 0xA3FFFFFF.toInt()
    const val W50 = 0xFF808080.toInt()
    const val W40 = 0x66FFFFFF
    const val W32 = 0x52FFFFFF
    const val W10 = 0x1AFFFFFF
    const val ACCENT = 0xFF8C9EFF.toInt()
    const val ALERT = 0xFFFF8A80.toInt()
    const val SCRIM = 0xA3000000.toInt()
    val WAKE_BG = intArrayOf(0xFF131417.toInt(), 0xFF17181C.toInt(), 0xFF1C1D21.toInt(), 0xFF222327.toInt(), 0xFF26272B.toInt())
    const val WAKE_PANEL = 0xFF2C2D32.toInt()
    const val WAKE_TRACK = 0xFF3A3B40.toInt()

    /** 들어올 때 ease-out(0.16, 1, 0.3, 1) */
    fun easeOut() = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    /** 화면이 부드럽게 넘어감: alpha 0 → 1, translationY 8dp → 0, 200ms */
    fun enter(v: View) {
        v.alpha = 0f; v.translationY = v.dp(8).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(easeOut()).start()
    }

    /** 차례로 켜짐: scale 0.8 → 1, alpha 0.2 → 1, 200ms */
    fun pop(v: View) {
        v.scaleX = 0.8f; v.scaleY = 0.8f; v.alpha = 0.2f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).setInterpolator(easeOut()).start()
    }
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun Context.label(s: CharSequence, size: Int, color: Int = Ui.WHITE, weight: Int = 400, line: Int = 0, center: Boolean = false) = TextView(this).apply {
    text = s
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, size.toFloat())
    typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    includeFontPadding = false
    setLineHeight(dp(if (line > 0) line else (size * 1.5f).toInt()))
    if (size >= 60) letterSpacing = -1f / size
    if (center) gravity = Gravity.CENTER_HORIZONTAL
    // 한국어를 낱말(어절) 단위로 줄 바꿈(API 33 이상)
    if (android.os.Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
}

fun Context.rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke > 0) setStroke(dp(stroke), strokeColor)
}

fun Context.oval(color: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(color); if (stroke > 0) setStroke(dp(stroke), strokeColor)
}

/** 판: 둥글기 32, 판 색 */
fun Context.panel(color: Int = Ui.PANEL) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(color, 32)
    setPadding(dp(40), dp(32), dp(40), dp(32))
}

fun Context.column(gravity: Int = Gravity.START) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; this.gravity = gravity }
fun Context.row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }
fun Context.gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(h), dp(h)) }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** 버튼: 주 버튼(104), 옅은 버튼(88), 글 버튼(88), 작은 옅은 버튼(72), 소개 버튼(120) */
class Btn(ctx: Context, val kind: Kind, text: String, icon: String? = null) : LinearLayout(ctx) {
    enum class Kind { PRIMARY, TONAL, TEXT, SMALL, INTRO }

    val label: TextView
    private val iconView: IconView?

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val (size, weight, fg) = when (kind) {
            Kind.PRIMARY -> Triple(32, 800, Ui.ON_PRIMARY)
            Kind.INTRO -> Triple(36, 800, Ui.ON_PRIMARY)
            Kind.TONAL, Kind.SMALL -> Triple(28, 700, Ui.WHITE)
            Kind.TEXT -> Triple(28, 700, Ui.W84)
        }
        background = when (kind) {
            Kind.PRIMARY, Kind.INTRO -> states(Ui.PRIMARY, Ui.PRIMARY_DOWN)
            Kind.TONAL, Kind.SMALL -> states(Ui.TRACK, Ui.TONAL_DOWN)
            Kind.TEXT -> null
        }
        if (kind == Kind.SMALL) setPadding(dp(32), 0, dp(32), 0) else setPadding(dp(24), 0, dp(24), 0)
        iconView = icon?.let {
            val s = if (kind == Kind.PRIMARY || kind == Kind.INTRO) 40 else 32
            IconView(ctx, it, if (kind == Kind.PRIMARY || kind == Kind.INTRO) Ui.ON_PRIMARY else Ui.W84).also { v -> addView(v, LayoutParams(dp(s), dp(s)).apply { rightMargin = dp(16) }) }
        }
        label = ctx.label(text, size, fg, weight)
        addView(label, LayoutParams(WRAP, WRAP))
        isClickable = true
        isFocusable = true
        setOnTouchListener { v, e ->
            if (v.isEnabled) when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            false
        }
    }

    private fun states(normal: Int, down: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), context.rounded(down, 24))
        addState(intArrayOf(), context.rounded(normal, 24))
    }

    fun setText(s: String) { label.text = s }

    fun onClick(f: () -> Unit): Btn { setOnClickListener { f() }; return this }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }

    companion object {
        fun height(kind: Kind) = when (kind) { Kind.PRIMARY -> 104; Kind.INTRO -> 120; Kind.SMALL -> 72; else -> 88 }
    }
}

/** 스위치 88×48. 켜짐 남빛 + 손잡이 #131417, 꺼짐 #313236 + 손잡이 흰 64% */
class Toggle(ctx: Context, var on: Boolean, private val onToggle: (Boolean) -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { on = !on; invalidate(); onToggle(on) }
    }

    fun set(v: Boolean) { if (on != v) { on = v; invalidate() } }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(88), dp(48))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = if (on) Ui.ACCENT else Ui.TRACK
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        val r = dp(18).toFloat(); val m = dp(6).toFloat()
        p.color = if (on) Ui.ON_PRIMARY else Ui.W64
        c.drawCircle(if (on) w - m - r else m + r, h / 2, r, p)
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.38f }
}

/** 앱 아이콘 48: 남빛 원 안에 #131417 원(r14, 굵기 4)과 바늘 */
class AppIcon(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.BG; style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = width / 48f
        c.save(); c.scale(s, s)
        c.drawCircle(24f, 24f, 24f, fill)
        c.drawCircle(24f, 24f, 14f, line)
        c.drawPath(Path().apply { moveTo(24f, 16f); lineTo(24f, 24f); lineTo(30f, 28f) }, line)
        c.restore()
    }
}

/** 선 아이콘(24 격자, 선 굵기 2, 끝 둥글게). Canvas의 Path로 그립니다. */
class IconView(ctx: Context, var name: String, var color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    fun set(name: String = this.name, color: Int = this.color) { this.name = name; this.color = color; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f
        c.save()
        c.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        c.scale(s, s)
        p.color = color
        p.strokeWidth = 2f
        Icons.draw(c, name, p)
        c.restore()
    }
}

object Icons {
    fun draw(c: Canvas, name: String, p: Paint) {
        when (name) {
            "lock" -> {
                c.drawRoundRect(RectF(5f, 11f, 19f, 21f), 2f, 2f, p)
                c.drawPath(Path().apply { moveTo(8f, 11f); lineTo(8f, 7.5f); arcTo(RectF(8f, 3.5f, 16f, 11.5f), 180f, 180f); lineTo(16f, 11f) }, p)
            }
            "bulb" -> {
                c.drawPath(Path().apply {
                    moveTo(9.5f, 15.5f); lineTo(9.5f, 14.5f)
                    arcTo(RectF(6f, 3f, 18f, 15f), 125f, 290f)
                    lineTo(14.5f, 15.5f); close()
                }, p)
                c.drawLine(9.5f, 18.5f, 14.5f, 18.5f, p)
                c.drawLine(10.5f, 21f, 13.5f, 21f, p)
            }
            "cabin" -> {
                c.drawArc(RectF(6f, 6f, 18f, 18f), 180f, 180f, false, p)
                c.drawLine(4f, 12f, 20f, 12f, p)
                c.drawLine(12f, 15f, 12f, 18f, p); c.drawLine(7.5f, 14.5f, 6f, 17f, p); c.drawLine(16.5f, 14.5f, 18f, 17f, p)
            }
            "note" -> {
                c.drawPath(Path().apply { moveTo(9f, 18f); lineTo(9f, 5f); lineTo(19f, 3f); lineTo(19f, 16f) }, p)
                c.drawCircle(6.5f, 18f, 2.5f, p); c.drawCircle(16.5f, 16f, 2.5f, p)
            }
            "air" -> {
                c.drawLine(3f, 8f, 14f, 8f, p); c.drawArc(RectF(11f, 2f, 17f, 8f), 90f, -270f, false, p)
                c.drawLine(3f, 12f, 17f, 12f, p); c.drawArc(RectF(14f, 12f, 20f, 18f), 270f, 270f, false, p)
                c.drawLine(3f, 16f, 10f, 16f, p)
            }
            "fan" -> {
                c.drawCircle(12f, 12f, 1.5f, p)
                for (i in 0 until 3) { c.save(); c.rotate(i * 120f, 12f, 12f); c.drawOval(RectF(10f, 2.5f, 14f, 10.5f), p); c.restore() }
            }
            "temp" -> {
                c.drawPath(Path().apply { moveTo(10f, 14f); lineTo(10f, 5f); arcTo(RectF(10f, 3f, 14f, 7f), 180f, 180f); lineTo(14f, 14f) }, p)
                c.drawArc(RectF(8f, 13f, 16f, 21f), -60f, 300f, false, p)
            }
            "seat" -> {
                c.drawPath(Path().apply { moveTo(8f, 3f); lineTo(9f, 14f); lineTo(17f, 14f); lineTo(18f, 21f) }, p)
                c.drawLine(9f, 14f, 8f, 21f, p)
            }
            "speech" -> {
                c.drawPath(Path().apply {
                    addRoundRect(RectF(3f, 4f, 21f, 16f), 3f, 3f, Path.Direction.CW)
                }, p)
                c.drawPath(Path().apply { moveTo(8f, 16f); lineTo(7f, 20.5f); lineTo(12.5f, 16f) }, p)
            }
            "shield" -> c.drawPath(Path().apply {
                moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); cubicTo(19f, 16f, 16f, 19.5f, 12f, 21f)
                cubicTo(8f, 19.5f, 5f, 16f, 5f, 11f); lineTo(5f, 6f); close()
            }, p)
            "clock" -> {
                c.drawCircle(12f, 12f, 9f, p)
                c.drawPath(Path().apply { moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15f, 14f) }, p)
            }
            "moon" -> c.drawPath(Path().apply {
                // 바깥 원(12,12 r9)과 안쪽 원(16.5,8.5 r8)이 만드는 초승달
                arcTo(RectF(3f, 3f, 21f, 21f), 23.28f, 237.7f, true); arcTo(RectF(8.5f, 0.5f, 24.5f, 16.5f), -137.66f, -199.57f); close()
            }, p)
            "sun" -> {
                c.drawCircle(12f, 12f, 4f, p)
                for (i in 0 until 8) { c.save(); c.rotate(i * 45f, 12f, 12f); c.drawLine(12f, 2.5f, 12f, 4.5f, p); c.restore() }
            }
            "restore" -> {
                c.drawArc(RectF(4f, 4f, 20f, 20f), 180f, 270f, false, p)
                c.drawLine(4f, 12f, 1.8f, 9f, p); c.drawLine(4f, 12f, 6.8f, 9.6f, p)
                c.drawPath(Path().apply { moveTo(12f, 8f); lineTo(12f, 12f); lineTo(14.5f, 13.5f) }, p)
            }
            "check" -> c.drawPath(Path().apply { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }, p)
            "bell" -> {
                c.drawPath(Path().apply {
                    moveTo(6f, 16f); lineTo(6f, 11f); arcTo(RectF(6f, 5f, 18f, 17f), 180f, 180f); lineTo(18f, 16f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close()
                }, p)
                c.drawLine(10.5f, 21f, 13.5f, 21f, p); c.drawLine(12f, 3f, 12f, 5f, p)
            }
            "calendar" -> {
                c.drawRoundRect(RectF(4f, 5f, 20f, 20f), 2f, 2f, p)
                c.drawLine(4f, 10f, 20f, 10f, p); c.drawLine(8f, 3f, 8f, 7f, p); c.drawLine(16f, 3f, 16f, 7f, p)
            }
            "door" -> {
                c.drawPath(Path().apply { moveTo(6f, 21f); lineTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f) }, p)
                c.drawLine(4f, 21f, 20f, 21f, p); c.drawCircle(14.5f, 12f, 0.6f, p)
            }
            "gear" -> {
                c.drawRoundRect(RectF(4f, 4f, 20f, 20f), 3f, 3f, p)
                c.drawPath(Path().apply { moveTo(10f, 17f); lineTo(10f, 7f); lineTo(13f, 7f); arcTo(RectF(10f, 7f, 16f, 13f), 270f, 180f); lineTo(10f, 13f) }, p)
            }
            "speaker" -> {
                c.drawPath(Path().apply { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close() }, p)
                c.drawArc(RectF(12f, 8.5f, 19f, 15.5f), -50f, 100f, false, p)
                c.drawArc(RectF(11f, 5f, 22f, 19f), -50f, 100f, false, p)
            }
            "alert" -> {
                c.drawCircle(12f, 12f, 9f, p); c.drawLine(12f, 7.5f, 12f, 13f, p); c.drawLine(12f, 16.5f, 12f, 16.6f, p)
            }
        }
    }
}

/**
 * 고리 576×576(크기에 맞춰 줄어듦). 반지름 258, 굵기 24, 빈 길 #313236, 채운 호 남빛(-90도에서 시계 방향, 끝 둥글게).
 * segments=4면 깨우는 고리(토막 78도, 사이 12도, 끝 자름). 열릴 때 한 번 0 → 지금 값으로 300ms 차오릅니다.
 */
class RingView(ctx: Context, private val ticks: Int, private val major: Int = -1, private val bell: Boolean = false, private val segments: Int = 0) : View(ctx) {
    var fraction = 0f; private set
    private var shown = 0f
    private var dotAlpha = 0f
    var lit = 0; private set
    private val segAlpha = FloatArray(4) { 1f }
    var trackColor = Ui.TRACK
    var panelColor = Ui.PANEL
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animated = false

    fun set(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        if (animated) { shown = fraction; invalidate() }
    }

    fun setLit(n: Int) {
        val old = lit
        lit = n.coerceIn(0, 4)
        if (animated && lit > old) for (i in old until lit) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300; interpolator = Ui.easeOut()
                addUpdateListener { segAlpha[i] = it.animatedValue as Float; invalidate() }
            }.start()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) return
        animated = true
        if (segments > 0) { invalidate(); return }
        ValueAnimator.ofFloat(0f, fraction).apply {
            duration = 300; startDelay = 100; interpolator = Ui.easeOut()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
        }.start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100; startDelay = 400
            addUpdateListener { dotAlpha = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val k = size / 576f
        val cx = width / 2f; val cy = height / 2f
        val r = 258f * k
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        p.strokeWidth = 24f * k
        if (segments > 0) {
            p.strokeCap = Paint.Cap.BUTT
            for (i in 0 until segments) {
                p.color = trackColor
                c.drawArc(rect, -90f + i * 90f + 6f, 78f, false, p)
                if (i < lit) {
                    p.color = Ui.ACCENT; p.alpha = (255 * segAlpha[i]).toInt()
                    c.drawArc(rect, -90f + i * 90f + 6f, 78f, false, p)
                    p.alpha = 255
                }
            }
            return
        }
        p.color = trackColor; p.strokeCap = Paint.Cap.BUTT
        c.drawCircle(cx, cy, r, p)
        // 눈금
        p.strokeWidth = 3f * k; p.strokeCap = Paint.Cap.ROUND; p.color = Ui.W64
        for (i in 1 until ticks) {
            val len = if (i == major) 18f else 12f
            c.save(); c.rotate(i * 360f / ticks, cx, cy)
            c.drawLine(cx, cy - r + 40f * k, cx, cy - r + (40f + len) * k, p)
            c.restore()
        }
        if (shown > 0.001f) {
            p.strokeWidth = 24f * k; p.strokeCap = Paint.Cap.ROUND; p.color = Ui.ACCENT
            if (shown >= 0.999f) c.drawCircle(cx, cy, r, p.apply { strokeCap = Paint.Cap.BUTT })
            else {
                c.drawArc(rect, -90f, 360f * shown, false, p)
                val a = Math.toRadians((-90.0 + 360.0 * shown))
                fill.color = panelColor; fill.alpha = (255 * dotAlpha).toInt()
                c.drawCircle(cx + r * Math.cos(a).toFloat(), cy + r * Math.sin(a).toFloat(), 6f * k, fill)
                fill.alpha = 255
            }
        }
        if (bell) {
            fill.color = panelColor
            c.drawCircle(cx, cy - r, 27f * k, fill)
            c.save()
            val s = 30f * k / 24f
            c.translate(cx - 15f * k, cy - r - 15f * k); c.scale(s, s)
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Ui.W84 }
            Icons.draw(c, "bell", bp)
            c.restore()
        }
    }
}

/** 차례 점 넷(재우는 중): 점 24, 사이 48, 아래 16에 이름 24 */
class StepDots(ctx: Context, names: List<String>) : LinearLayout(ctx) {
    private val dots = ArrayList<View>()
    private val labels = ArrayList<TextView>()
    private var on = BooleanArray(names.size)

    init {
        orientation = HORIZONTAL
        names.forEachIndexed { i, n ->
            val col = ctx.column(Gravity.CENTER_HORIZONTAL)
            val d = View(ctx).apply { background = ctx.oval(Ui.TRACK) }
            col.addView(d, LayoutParams(dp(24), dp(24)))
            val t = ctx.label(n, 24, Ui.W64, 700, 32, center = true)
            col.addView(t, LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
            addView(col, LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(48) })
            dots.add(d); labels.add(t)
        }
    }

    fun set(lit: BooleanArray, animate: Boolean) {
        lit.forEachIndexed { i, v ->
            if (v == on[i]) return@forEachIndexed
            dots[i].background = context.oval(if (v) Ui.ACCENT else Ui.TRACK)
            labels[i].setTextColor(if (v) Ui.WHITE else Ui.W64)
            if (v && animate) Ui.pop(dots[i])
        }
        on = lit.copyOf()
    }
}

/** 분 고르는 칸 하나: 위 "지난번", 아래 숫자 48 + "분" 28. 고르면 #E0E1E6 위 #131417 */
class MinuteCell(ctx: Context, val minutes: Int, last: Boolean) : LinearLayout(ctx) {
    private val top = ctx.label(if (last) "지난번" else "", 24, Ui.W64, 600, 32, center = true)
    private val num = ctx.label("$minutes", 48, Ui.WHITE, 800, 64)
    private val unit = ctx.label("분", 28, Ui.W84, 700, 44)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(top, LayoutParams(MATCH, WRAP))
        val r = ctx.row(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
        r.addView(num); r.addView(unit, LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
        addView(r, LayoutParams(MATCH, WRAP))
        isClickable = true
        mark(false)
    }

    fun mark(on: Boolean) {
        background = context.rounded(if (on) Ui.PRIMARY else Ui.TRACK, 24)
        num.setTextColor(if (on) Ui.ON_PRIMARY else Ui.WHITE)
        unit.setTextColor(if (on) Ui.ON_PRIMARY else Ui.W84)
        top.setTextColor(if (on) Ui.ON_PRIMARY else Ui.W64)
    }
}
