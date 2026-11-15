package kr.carlarm.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 0.3: 화면을 새 모양(어두운 판, 큰 고리, 남빛 하나)으로 옮겼습니다. 기능(Car, Nap, Remind, Voice, CarlarmService)은 0.2 그대로이고,
 * 화면이 부르는 기능 호출은 docs/화면_옮김_기록.md의 대조표와 같습니다.
 * 한 화면은 지금 상태에서 할 일 하나를 묻고(큰 문장 하나, 주 버튼 하나), 시험용 조작은 "시연 도구" 판에만 둡니다.
 */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var body: FrameLayout
    private lateinit var fastDot: View
    private var screen = ""
    private var refresh: (() -> Unit)? = null
    private var tools: View? = null
    private var toolsRefresh: (() -> Unit)? = null
    private var prepPick = -1
    private var hintText = ""

    private val prefs get() = Records.prefs

    private val ticker = object : Runnable {
        override fun run() {
            if (screen != "intro" && screen != "ask") refresh?.invoke()
            toolsRefresh?.invoke()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Records.init(this)
        Car.start(this)
        Voice.start(this)
        Nap.fast = prefs.getBoolean("fast", false)
        // Nap.onGear()는 Car가 기어 콜백에서 직접 부릅니다(여기서 부르면 두 번 불림).
        Car.onGear = {
            // 권한 뒤 P에 서면 시작 화면이 상태에 맞는 말("…넘게 달렸으니 쉬어 가요.")과 "지난번처럼 N분 쉬기"를 먼저 건넵니다(0.2는 쪽잠 준비를 바로 열었음).
            if (Car.isPark && Remind.spoke && !Remind.prepShown && Nap.state == State.IDLE) { Remind.prepShown = true; go("home") }
            else if (Nap.state == State.IDLE) render()
        }
        Nap.onChange = { render() }
        Remind.onTick = { if (screen == "home") refresh?.invoke(); toolsRefresh?.invoke() }
        Remind.onChange = { if (screen == "home") refresh?.invoke(); toolsRefresh?.invoke() }
        // 앱이 죽었다 켜졌는데 적어 둔 쪽잠이나 쉼 알림이 있으면 서비스를 켜서 남은 차례를 잇습니다.
        CarlarmService.recoverIfNeeded(this)

        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        body = FrameLayout(this)
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        // 빠르게 보기가 켜진 동안: 머리줄 오른쪽 끝의 남빛 점 16
        fastDot = View(this).apply { background = oval(Ui.ACCENT) }
        root.addView(fastDot, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { topMargin = dp(48 + 24); rightMargin = dp(64) })
        setContentView(root)
        render(force = true)
        // 깨울 때 다른 앱이 앞에 있으면 '일어났어요' 버튼이 달린 알림을 띄우려면 알림 권한이 필요합니다(Android 13 이상, 처음 한 번만 묻기).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notiAsked", false)) {
            prefs.edit().putBoolean("notiAsked", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        if (testHooks) {
            intent?.getStringExtra("ambientTest")?.let { ambientTest(it) }
            intent?.getStringExtra("devTest")?.let { devTest(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        CarlarmService.uiVisible = true
        CarlarmService.wakeAlert(false)   // 칼람 화면이 앞에 오면 깨우는 화면의 버튼을 쓰므로 알림은 거둠
        render()
        main.removeCallbacks(ticker); main.post(ticker)
    }

    override fun onPause() {
        CarlarmService.uiVisible = false
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (testHooks) {
            intent?.getStringExtra("ambientTest")?.let { ambientTest(it) }
            intent?.getStringExtra("devTest")?.let { devTest(it) }
        }
    }

    @Deprecated("Activity의 뒤로 가기")
    override fun onBackPressed() {
        when {
            tools != null -> closeTools()
            Nap.state == State.IDLE && screen == "prep" -> go("home")
            Nap.state == State.IDLE && screen == "intro" && prefs.getBoolean("introSeen", false) -> go(nextAfterIntro())
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** 시험용 입구(devTest, ambientTest)는 디버그 빌드에서만 받습니다. 출시판(release)은 디버그 가능 표시가 없어 받지 않으므로,
     *  다른 앱이 인텐트로 칼람을 열어 문 잠금 같은 차 값을 바꾸게 할 수 없습니다. */
    private val testHooks: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 시험용: "장치:값" 하나를 보내고 2.5초 뒤 다시 읽어 기록합니다(adb로만 부름). 예: lock:true, fan:3, trip:0 */
    private fun devTest(arg: String) {
        val (k, v) = arg.split(":").let { it[0] to it.getOrElse(1) { "" } }
        fun <T : Any> run(d: Dev<T>, value: T) = d.set(value) { ok ->
            main.postDelayed({ d.get { now -> log("장치 시험: ${d.name} 보냄 $value, SDK 답 ${if (ok) "성공" else "실패"}, 2.5초 뒤 다시 읽음 $now") } }, 2500)
        }
        val sw = if (v == "on") ai.pleos.playground.vehicle.constant.control.LightSwitch.ON else ai.pleos.playground.vehicle.constant.control.LightSwitch.OFF
        when (k) {
            "amb" -> run(Car.ambient, v.toInt())
            "ambsw" -> run(Car.ambientSwitch, sw)
            "cabin" -> run(Car.cabin, sw)
            "lock" -> run(Car.doorLock, v == "true")
            "fan" -> run(Car.fan, v.toInt())
            "recirc" -> run(Car.recirc, v == "true")
            "seatheat" -> run(Car.seatHeat, v.toInt())
            "read" -> listOf<Dev<*>>(Car.ambient, Car.ambientSwitch, Car.cabin, Car.doorLock, Car.fan, Car.recirc, Car.seatHeat).forEach { d -> d.get { now -> log("장치 시험: ${d.name} 지금 값 $now") } }
            "speak" -> main.postDelayed({ Voice.say("두 시간 달렸으니 다음 쉼터에서 쉬어 가요.") }, (v.toIntOrNull() ?: 5) * 1000L)
            "recircwatch" -> Car.watchRecirc()
            "color" -> run(Car.ambientColor, java.lang.Long.parseLong(v, 16).toInt())
            "aaos" -> Car.readAaos()
            "lockcap" -> Car.checkLock { r -> log("장치 시험: 문 잠금 능력 $r") }
            "trip" -> Car.tripMinutes { m -> log("장치 시험: 달린 시간 $m (오류: ${Car.tripError})") }
        }
    }

    /** 시험용: 무드등 밝기를 차례로 보내고 2.5초 뒤 다시 읽어 기록합니다(adb로만 부름). */
    private fun ambientTest(list: String) {
        val values = list.split(",").mapNotNull { it.trim().toIntOrNull() }
        fun next(i: Int) {
            if (i >= values.size) return
            val v = values[i]
            Car.ambient.set(v) { ok ->
                main.postDelayed({ Car.ambient.get { now -> log("무드등 시험: 보냄 $v, SDK 답 ${if (ok) "성공" else "실패"}, 2.5초 뒤 다시 읽음 $now"); main.postDelayed({ next(i + 1) }, 2500) } }, 2500)
            }
        }
        next(0)
    }

    // ───── 화면 고르기 ─────

    private fun nextAfterIntro() = if (prefs.getBoolean("asked", false)) "home" else "ask"

    private fun render(force: Boolean = false) {
        fastDot.visibility = if (Nap.fast) View.VISIBLE else View.GONE
        val want = when (Nap.state) {
            State.IDLE -> when {
                screen == "prep" || screen == "ask" || screen == "intro" -> screen
                !prefs.getBoolean("introSeen", false) -> "intro"
                !prefs.getBoolean("asked", false) -> "ask"
                else -> "home"
            }
            State.FALLING -> "falling"
            State.ASLEEP -> "sleep"
            State.WAKING -> "wake"
            State.RESTORING, State.RESTORED -> "restored"
        }
        if (!force && want == screen) { refresh?.invoke(); return }
        show(want, animate = true)
    }

    private fun show(want: String, animate: Boolean) {
        screen = want
        refresh = null
        body.removeAllViews()
        val v = when (want) {
            "intro" -> introScreen()
            "ask" -> askScreen()
            "prep" -> prepScreen()
            "falling" -> fallingScreen()
            "sleep" -> sleepScreen()
            "wake" -> wakeScreen()
            "restored" -> restoredScreen()
            else -> homeScreen()
        }
        body.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        if (animate) Ui.enter(v)
    }

    private fun go(s: String) { show(s, animate = true) }

    // ───── 짜임: 머리줄, 본문, 두 단 ─────

    private fun frame(content: View, footer: View? = null, back: Boolean = false): LinearLayout {
        val col = column().apply { setPadding(dp(64), dp(48), dp(64), dp(40)) }
        val head = row()
        head.addView(AppIcon(this), lp(dp(48), dp(48)))
        head.addView(label("칼람", 40, Ui.WHITE, 800, 64), lp(WRAP, WRAP).apply { leftMargin = dp(16) })
        head.addView(View(this), lp(0, 1, 1f))
        if (back) head.addView(label("돌아가기", 24, Ui.W64, 600, 48).apply { setOnClickListener { go("home") }; setPadding(dp(16), 0, dp(16), 0) },
            lp(WRAP, WRAP).apply { rightMargin = dp(40) })
        col.addView(head, lp(MATCH, dp(64)))
        val holder = FrameLayout(this)
        holder.addView(content, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        col.addView(holder, lp(MATCH, 0, 1f).apply { topMargin = dp(40) })
        footer?.let { col.addView(it, lp(MATCH, dp(48)).apply { topMargin = dp(24) }) }
        return col
    }

    private fun twoCol(left: View, right: View, leftW: Int = 720, gapW: Int = 96): LinearLayout {
        val r = row(Gravity.CENTER_VERTICAL)
        r.addView(left, lp(dp(leftW), WRAP))
        r.addView(right, lp(0, WRAP, 1f).apply { leftMargin = dp(gapW) })
        return r
    }

    /** 판 줄: 위 선 1, 위아래 여백 24, 아이콘 40 + 사이 24 + 글 + 오른쪽 스위치나 값 */
    private class PanelRow(val view: LinearLayout, val title: TextView, val sub: TextView)

    private fun panelRow(icon: String, title: String, sub: String, right: View?): PanelRow {
        val wrap = column()
        wrap.addView(View(this).apply { setBackgroundColor(Ui.W10) }, lp(MATCH, 1))
        val r = row()
        r.setPadding(0, dp(24), 0, dp(24))
        r.addView(IconView(this, icon, Ui.W84), lp(dp(40), dp(40)))
        val texts = column()
        val t = label(title, 32, Ui.WHITE, 700, 48)
        val s = label(sub, 26, Ui.W64, 400, 40)
        texts.addView(t); texts.addView(s)
        r.addView(texts, lp(0, WRAP, 1f).apply { leftMargin = dp(24) })
        right?.let { r.addView(it, lp(WRAP, WRAP).apply { leftMargin = dp(16) }) }
        wrap.addView(r, lp(MATCH, WRAP))
        return PanelRow(wrap, t, s)
    }

    private fun valueText(s: String) = label(s, 32, Ui.ACCENT, 800, 48)

    private fun btn(kind: Btn.Kind, text: String, icon: String? = null, f: () -> Unit) = Btn(this, kind, text, icon).onClick(f)

    private fun addBtn(parent: LinearLayout, b: Btn, top: Int, width: Int = MATCH) =
        parent.addView(b, lp(width, dp(Btn.height(b.kind))).apply { topMargin = dp(top) })

    /** 쪽지: 아이콘 32(위 4 내림) + 사이 16 + 26/40 64% */
    private fun note(icon: String, s: String): Pair<LinearLayout, TextView> {
        val r = row(Gravity.TOP)
        r.addView(IconView(this, icon, Ui.W64), lp(dp(32), dp(32)).apply { topMargin = dp(4) })
        val t = label(s, 26, Ui.W64, 400, 40)
        r.addView(t, lp(0, WRAP, 1f).apply { leftMargin = dp(16) })
        return r to t
    }

    /** 알림 띠: 판 색, 안쪽 테두리 2 #FF8A80, 아이콘 40 + 사이 16, 28/44 700 */
    private fun alertBand(icon: String, s: String): Pair<LinearLayout, TextView> {
        val r = row()
        r.background = rounded(Ui.PANEL, 24, 2, Ui.ALERT)
        r.setPadding(dp(24), dp(20), dp(24), dp(20))
        r.minimumHeight = dp(88)
        r.addView(IconView(this, icon, Ui.ALERT), lp(dp(40), dp(40)))
        val t = label(s, 28, Ui.WHITE, 700, 44)
        r.addView(t, lp(0, WRAP, 1f).apply { leftMargin = dp(16) })
        return r to t
    }

    private fun bigText(s: String) = label(s, 60, Ui.WHITE, 800, 90)

    /** 고리와 고리 안 글(세로 가운데) */
    private fun ringBox(ring: RingView, inner: View, size: Int = 576): FrameLayout {
        val f = FrameLayout(this)
        f.addView(ring, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
        if (inner is LinearLayout) for (i in 0 until inner.childCount) {
            val c = inner.getChildAt(i)
            if (c is TextView) { c.layoutParams = (c.layoutParams as? LinearLayout.LayoutParams ?: lp(MATCH, WRAP)).apply { width = MATCH }; c.gravity = Gravity.CENTER_HORIZONTAL }
        }
        f.addView(inner, FrameLayout.LayoutParams(dp(size * 0.8f), WRAP, Gravity.CENTER))
        return f
    }

    /** 숫자와 단위: "1"120 + "시간"40 + "12"120 + "분"40 */
    private class Clock(val view: LinearLayout, val n1: TextView, val u1: TextView, val n2: TextView, val u2: TextView)

    private fun clock(): Clock {
        val r = row(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
        r.isBaselineAligned = true
        fun num() = label("", 120, Ui.WHITE, 800, 144).apply { fontFeatureSettings = "tnum"; letterSpacing = -2f / 120 }
        fun unit() = label("", 40, Ui.W84, 700, 64)
        val n1 = num(); val u1 = unit(); val n2 = num(); val u2 = unit()
        r.addView(n1); r.addView(u1, lp(WRAP, WRAP).apply { leftMargin = dp(8); rightMargin = dp(16) }); r.addView(n2); r.addView(u2, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        return Clock(r, n1, u1, n2, u2)
    }

    private fun Clock.set(a: String, au: String, b: String = "", bu: String = "") {
        n1.text = a; u1.text = au; n2.text = b; u2.text = bu
        val second = if (b.isEmpty()) View.GONE else View.VISIBLE
        n2.visibility = second; u2.visibility = second
        u1.visibility = if (au.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun innerCol() = column(Gravity.CENTER_HORIZONTAL)

    /** 고리 안 글: 너비를 고리 안쪽에 맞추고 가운데 정렬 */
    private fun ringText(s: String, size: Int, color: Int, weight: Int, line: Int) = label(s, size, color, weight, line, center = true).apply { gravity = Gravity.CENTER }

    // ───── 값 읽기(화면에 쓸 글) ─────

    private fun records(): JSONArray = try { JSONArray(prefs.getString("records", "[]")) } catch (_: Exception) { JSONArray() }
    private fun hasRecords() = Records.lastMinutes() != null
    private fun lastMinutes() = Records.lastMinutes() ?: prefs.getInt("minutes", 20)

    /** "이번 주 2번, 35분 쉬었어요" (지난 7일). 없으면 "아직 쉰 기록이 없어요" */
    private fun weekLine(prefix: Boolean = true): String {
        val n = Records.weekCount()
        if (n == 0) return "아직 쉰 기록이 없어요"
        return (if (prefix) "이번 주 " else "") + "${n}번, ${minText(Records.weekMinutes() * 60)} 쉬었어요"
    }

    private fun minText(sec: Int): String {
        val m = (sec / 60.0).roundToInt()
        return if (m >= 60) "${m / 60}시간" + (if (m % 60 > 0) " ${m % 60}분" else "") else "${m}분"
    }

    private fun lastSleptMin(): Int {
        val a = records()
        if (a.length() == 0) return Nap.minutes
        return (a.optJSONObject(a.length() - 1)?.optInt("sec") ?: 0).let { (it / 60.0).roundToInt() }
    }

    private fun phrase(min: Int) = when (min) { 60 -> "한 시간"; 90 -> "한 시간 반"; else -> "두 시간" }

    /** "오후 7시 13분에 깨울게요" (분이 0이면 "오후 7시에 깨울게요") */
    private fun wakeLine(secFromNow: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.SECOND, secFromNow) }
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "${if (h < 12) "오전" else "오후"} ${h12}시" + (if (m > 0) " ${m}분" else "") + "에 깨울게요"
    }

    private fun josa(word: String, withBatchim: String, without: String): String {
        val c = word.lastOrNull() ?: return word + without
        return word + if (c in '가'..'힣' && (c - '가') % 28 != 0) withBatchim else without
    }

    // ───── 소개 (처음 한 번, 시작 화면의 "칼람 소개") ─────

    private fun introScreen(): View {
        val first = !prefs.getBoolean("introSeen", false)
        val c = innerCol()
        c.addView(label("차가 울리는 알람, 칼람", 60, Ui.WHITE, 800, 90, center = true))
        c.addView(label("쉴 때를 알고, 재우고, 깨웁니다", 30, Ui.W84, 500, 48, center = true), lp(WRAP, WRAP).apply { topMargin = dp(8) })
        val steps = row(Gravity.TOP)
        val data = listOf(
            Triple(1, "알아요", "두 시간 달리면\n말로 한 번 알려요") to listOf("gear" to "기어", "clock" to "달린 시간", "speech" to "말 한 문장"),
            Triple(2, "재워요", "문을 잠그고, 빛을 끄고,\n잠들 때의 소리를 틀어요") to listOf("lock" to "문 잠금", "bulb" to "빛", "note" to "소리"),
            Triple(3, "깨워요", "빛과 바람과 소리로 깨우고,\n차를 쉬기 전으로 돌려놔요") to listOf("bulb" to "빛", "fan" to "바람", "restore" to "돌려놓기"),
        )
        data.forEach { (t, icons) ->
            val col = innerCol()
            col.addView(StepPic(this, t.first), lp(dp(160), dp(160)))
            col.addView(label(t.second, 40, Ui.WHITE, 800, 64, center = true), lp(WRAP, WRAP).apply { topMargin = dp(32) })
            col.addView(label(t.third, 28, Ui.W84, 500, 44, center = true), lp(WRAP, WRAP).apply { topMargin = dp(8) })
            val ir = row(Gravity.TOP)
            icons.forEachIndexed { i, (ic, name) ->
                val one = innerCol()
                one.addView(IconView(this, ic, Ui.W84), lp(dp(40), dp(40)))
                one.addView(label(name, 24, Ui.W64, 600, 32, center = true), lp(WRAP, WRAP).apply { topMargin = dp(8) })
                ir.addView(one, lp(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(40) })
            }
            col.addView(ir, lp(WRAP, WRAP).apply { topMargin = dp(32) })
            steps.addView(col, lp(0, WRAP, 1f))
        }
        c.addView(steps, lp(MATCH, WRAP).apply { topMargin = dp(80) })
        addBtn(c, btn(Btn.Kind.INTRO, if (first) "시작하기" else "돌아가기") {
            prefs.edit().putBoolean("introSeen", true).apply()
            go(nextAfterIntro())
        }, 80, dp(480))
        return frame(c)
    }

    // ───── 처음 물음 셋 (건너뛸 수 있음) ─────

    private fun askScreen(): View {
        val left = column()
        left.addView(bigText("어떻게 쉬면 좋은지\n세 가지만 여쭐게요"))
        left.addView(note("shield", "답은 이 차 안에만 두고, 쉼을 권하는 때와 깨우는 차례를 맞추는 데만 써요. 권하는 때는 두 시간을 넘기지 않아요.").first,
            lp(MATCH, WRAP).apply { topMargin = dp(32) })
        val picks = arrayOf(prefs.getInt("q1", -1), prefs.getInt("q2", 0), prefs.getInt("q3", -1)) // q2는 여러 개(비트)
        addBtn(left, btn(Btn.Kind.PRIMARY, "저장하기") {
            prefs.edit().putInt("q1", picks[0]).putInt("q2", picks[1]).putInt("q3", picks[2]).putBoolean("asked", true).apply()
            go("home")
        }, 64)
        addBtn(left, btn(Btn.Kind.TEXT, "건너뛰기") { prefs.edit().putBoolean("asked", true).apply(); go("home") }, 8)

        val right = column()
        val qs = listOf(
            "보통 얼마나 달리면 졸리세요?" to listOf("1시간쯤", "1시간 반쯤", "2시간쯤", "잘 모르겠어요"),
            "졸릴 때 무엇이 도움이 되나요?" to listOf("잠깐 자기", "바깥 공기", "음악", "잘 모르겠어요"),
            "어떻게 깨면 편하세요?" to listOf("빛부터 천천히", "바람과 함께", "소리로 바로"),
        )
        qs.forEachIndexed { qi, (q, opts) ->
            val head = row(Gravity.BOTTOM)
            head.addView(label(q, 32, Ui.WHITE, 700, 48))
            if (qi == 1) head.addView(label("여러 개 고를 수 있어요", 24, Ui.W64, 600, 40), lp(WRAP, WRAP).apply { leftMargin = dp(16) })
            right.addView(head, lp(MATCH, WRAP).apply { if (qi > 0) topMargin = dp(48) })
            val chips = row()
            val views = opts.mapIndexed { oi, o ->
                label(o, 28, Ui.WHITE, 600, 44).apply {
                    gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0); isClickable = true
                }.also { chips.addView(it, lp(WRAP, dp(88)).apply { if (oi > 0) leftMargin = dp(16) }) }
            }
            fun paint() = views.forEachIndexed { oi, v ->
                val on = if (qi == 1) (picks[1] shr oi) and 1 == 1 else picks[qi] == oi
                v.background = rounded(if (on) Ui.PRIMARY else Ui.TRACK, 24)
                v.setTextColor(if (on) Ui.ON_PRIMARY else Ui.WHITE)
                v.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (on) 800 else 600, false)
            }
            views.forEachIndexed { oi, v -> v.setOnClickListener { if (qi == 1) picks[1] = picks[1] xor (1 shl oi) else picks[qi] = oi; paint() } }
            paint()
            right.addView(chips, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        }
        return frame(twoCol(left, right, leftW = 560))
    }

    // ───── 시작 (다-2) ─────

    private fun homeKey(): String = when {
        !Car.isPark -> "notp"
        !Remind.on -> "off"
        Remind.drivenMin >= Remind.thresholdMin() -> "over"
        Remind.recircSaid && Car.recircOn != false -> "air"   // 환기를 권한 뒤 P. 바깥 공기로 바꾸면 사라짐
        Remind.drivenMin >= Remind.thresholdMin() / 2.0 -> "hour"
        else -> "fresh"
    }

    private fun homeScreen(): View {
        val key = homeKey()
        val th = Remind.thresholdMin(if (Remind.on) Remind.q1 else prefs.getInt("q1", -1))
        val hasLast = hasRecords()
        val n = lastMinutes()

        // 왼쪽 판: 고리(달린 시간, 두 시간이 한 바퀴) + 쉼 알림 줄
        val left = panel()
        val ring = RingView(this, ticks = 8, major = 4, bell = true)
        val inner = innerCol()
        val cap = label("", 28, Ui.W84, 500, 44, center = true)
        val clk = clock()
        val next = label("", 30, Ui.ACCENT, 700, 48, center = true)
        inner.addView(cap)
        inner.addView(clk.view, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        inner.addView(next, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        left.addView(ringBox(ring, inner), lp(MATCH, dp(576)))
        lateinit var rr: PanelRow
        val toggle = Toggle(this, Remind.on) { on ->
            if (on) {
                if (!Car.isPark) { rr.sub.text = "기어가 P일 때 켤 수 있어요"; main.postDelayed({ refresh?.invoke() }, 1500) }
                else Remind.start(prefs.getInt("q1", -1), prefs.getInt("q2", 0))
            } else Remind.stop()
            show("home", animate = false)
        }
        rr = panelRow("speaker", "쉼 알림", "${phrase(th)} 달리면 말로 한 번 알려요", toggle)
        left.addView(rr.view, lp(MATCH, WRAP).apply { topMargin = dp(16) })

        // 오른쪽 단: 큰 문장 → 기어 표지 → 주 버튼 → 옅은 버튼 → 기록 한 줄
        val right = column()
        val title = bigText(when (key) {
            "notp" -> "기어를 P에 두면 쪽잠을 잘 수 있어요."
            "off" -> "쉼 알림을 켜 둘까요?"
            "air" -> "바깥 공기로 바꿔 볼까요?"
            "over" -> "${phrase(th)} 넘게 달렸으니 쉬어 가요."
            "hour" -> "잠깐 쉬어 갈까요?"
            else -> "${phrase(th)} 달리면 말로 알려 드릴게요."
        })
        right.addView(title)
        val gearRow = row()
        val gName = Car.gearName()
        val box = label(if (Car.gear == null) "-" else gName.take(1), 24, if (Car.isPark) Ui.ON_PRIMARY else Ui.WHITE, 800, 32).apply {
            gravity = Gravity.CENTER; background = rounded(if (Car.isPark) Ui.WHITE else Ui.TRACK, 8)
        }
        gearRow.addView(box, lp(dp(40), dp(40)))
        gearRow.addView(label(if (Car.gear == null) "기어 확인 중" else "기어 $gName", 24, Ui.W84, 600, 32), lp(WRAP, WRAP).apply { leftMargin = dp(16) })
        right.addView(gearRow, lp(MATCH, WRAP).apply { topMargin = dp(64) })
        fun prepWith(m: Int) { prepPick = m; go("prep") }
        val (main1, tonal) = when (key) {
            "off" -> btn(Btn.Kind.PRIMARY, "쉼 알림 켜기", "bell") { Remind.start(prefs.getInt("q1", -1), prefs.getInt("q2", 0)); show("home", false) } to
                btn(Btn.Kind.TONAL, "쪽잠 준비", "moon") { go("prep") }
            "fresh" -> btn(Btn.Kind.PRIMARY, "쪽잠 준비", "moon") { go("prep") } to null
            "hour" -> btn(Btn.Kind.PRIMARY, "쪽잠 준비", "moon") { go("prep") } to
                (if (hasLast) btn(Btn.Kind.TONAL, "지난번처럼 ${n}분", "restore") { prepWith(n) } else null)
            "over" -> if (hasLast) btn(Btn.Kind.PRIMARY, "지난번처럼 ${n}분 쉬기", "moon") { prepWith(n) } to btn(Btn.Kind.TONAL, "시간 고르기", "clock") { go("prep") }
                else btn(Btn.Kind.PRIMARY, "쪽잠 준비", "moon") { go("prep") } to null
            "air" -> btn(Btn.Kind.PRIMARY, "바깥 공기로 바꾸기", "air") { Car.apply(Car.recirc, false) { ok -> log("바깥 공기로 바꾸기 ${if (ok) "됨" else "다시 읽기에서 확인되지 않음"}"); main.post { refresh?.invoke() } } } to
                btn(Btn.Kind.TONAL, "쪽잠 준비", "moon") { go("prep") }
            else -> btn(Btn.Kind.PRIMARY, "쪽잠 준비", "moon") { go("prep") }.apply { isEnabled = false } to
                (if (hasLast) btn(Btn.Kind.TONAL, "지난번처럼 ${n}분", "restore") { prepWith(n) }.apply { isEnabled = false } else null)
        }
        addBtn(right, main1, 16)
        tonal?.let { addBtn(right, it, 16) }
        val week = label("", 26, Ui.W64, 400, 40, center = true)
        right.addView(week, lp(MATCH, WRAP).apply { topMargin = dp(32) })

        // 바닥줄
        val foot = row()
        foot.addView(label("쉴 때를 알고, 재우고, 깨웁니다", 26, Ui.W64, 400, 40), lp(0, WRAP, 1f))
        foot.addView(label("칼람 소개", 24, Ui.W64, 600, 48).apply { setOnClickListener { go("intro") } })
        foot.addView(label("시연 도구", 24, Ui.W64, 600, 48).apply { setOnClickListener { openTools() } }, lp(WRAP, WRAP).apply { leftMargin = dp(48) })

        fun update() {
            if (homeKey() != key) { show("home", animate = true); return }
            val on = Remind.on
            val driven = Remind.drivenMin.toInt()
            val t = Remind.thresholdMin(if (on) Remind.q1 else prefs.getInt("q1", -1))
            toggle.set(on)
            ring.alpha = if (on) 1f else 0.5f
            ring.set(if (on) (Remind.drivenMin / 120.0).toFloat() else 0f)
            if (on) {
                cap.text = "쉬지 않고 달린 시간"; cap.setTextColor(Ui.W84)
                cap.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 28f)
                if (driven >= 60) clk.set("${driven / 60}", "시간", "${driven % 60}", "분") else clk.set("$driven", "분")
                clk.view.visibility = View.VISIBLE
                next.text = if (Remind.drivenMin < t) "${ceil(t - Remind.drivenMin).toInt()}분 뒤에 알려요" else "${phrase(t)}이 지났어요"
                next.setTextColor(Ui.ACCENT); next.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 30f)
            } else {
                cap.text = "쉼 알림이 꺼져 있어요"; cap.setTextColor(Ui.WHITE)
                cap.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 32f)
                clk.view.visibility = View.GONE
                next.text = "켜 두면 ${phrase(t)} 달렸을 때\n말로 한 번 알려요"
                next.setTextColor(Ui.W64); next.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 26f)
            }
            week.text = weekLine()
        }
        update()
        refresh = { update() }
        return frame(twoCol(left, right), foot)
    }

    // ───── 쪽잠 준비 ─────

    private fun prepScreen(): View {
        var pick = if (prepPick > 0) prepPick else lastMinutes()
        prepPick = -1
        val hasLast = hasRecords()
        val last = lastMinutes()

        val left = panel()
        val ring = RingView(this, ticks = 6).apply { set(pick / 30f) }
        val inner = innerCol()
        inner.addView(label("쉴 시간", 28, Ui.W84, 500, 44, center = true))
        val clk = clock().apply { set("$pick", "분") }
        inner.addView(clk.view, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        left.addView(ringBox(ring, inner), lp(MATCH, dp(576)))
        left.addView(View(this).apply { setBackgroundColor(Ui.W10) }, lp(MATCH, 1).apply { topMargin = dp(24) })
        left.addView(label("쉬는 동안 차가 하는 일", 24, Ui.W64, 600, 32), lp(MATCH, WRAP).apply { topMargin = dp(24) })
        val does = row(Gravity.TOP)
        var airName: TextView? = null
        var airValue: TextView? = null
        listOf(Triple("lock", "문 잠금", "쉬는 동안만"), Triple("bulb", "빛", "무드등 0%로"), Triple("note", "소리", "잠들 때의 소리"), Triple("air", "바깥 공기", "바람 1단계, 온도 +1℃")).forEach { (ic, name, v) ->
            val col = innerCol()
            val circle = FrameLayout(this).apply { background = oval(Ui.TRACK) }
            circle.addView(IconView(this, ic, Ui.WHITE), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            col.addView(circle, lp(dp(64), dp(64)))
            val nt = label(name, 24, Ui.WHITE, 700, 32, center = true)
            val vt = label(v, 20, Ui.W64, 400, 32, center = true)
            col.addView(nt, lp(WRAP, WRAP).apply { topMargin = dp(8) })
            col.addView(vt, lp(WRAP, WRAP))
            if (ic == "air") { airName = nt; airValue = vt }
            does.addView(col, lp(0, WRAP, 1f))
        }
        left.addView(does, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        // 공조가 꺼져 있으면 공기는 그대로 둡니다(Nap의 5초 차례와 같은 조건).
        Car.hvacPower.get { on ->
            if (on == false) { airName?.text = "공기"; airValue?.text = "그대로 둬요" }
            else if (on == true) Car.temp.get { t -> if (t != null) airValue?.text = "바람 1단계, ${(t + 1f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}℃" }
        }

        val right = column()
        val (band, bandText) = alertBand("door", "")
        band.visibility = View.GONE
        right.addView(band, lp(MATCH, WRAP).apply { bottomMargin = dp(32) })
        right.addView(bigText("얼마나 쉴까요?"))
        val pickRow = row()
        val wake = label("", 30, Ui.ACCENT, 700, 48)
        val start = Btn(this, Btn.Kind.PRIMARY, "", "moon")
        val cells = listOf(10, 15, 20, 30).mapIndexed { i, m ->
            MinuteCell(this, m, hasLast && m == last).also { pickRow.addView(it, lp(0, dp(128), 1f).apply { if (i > 0) leftMargin = dp(16) }) }
        }
        fun paint() {
            cells.forEach { it.mark(it.minutes == pick) }
            start.setText("${pick}분 쪽잠 시작")
            wake.text = wakeLine(pick * 60)
            clk.set("$pick", "분")
            ring.set(pick / 30f)
        }
        cells.forEach { c -> c.setOnClickListener { pick = c.minutes; paint() } }
        paint()
        right.addView(pickRow, lp(MATCH, WRAP).apply { topMargin = dp(40) })
        right.addView(wake, lp(MATCH, WRAP).apply { topMargin = dp(24) })
        start.onClick {
            prefs.edit().putInt("minutes", pick).apply()
            if (!Nap.start(pick, prefs.getInt("q3", 1).let { if (it < 0) 1 else it })) { bandText.text = "기어를 P에 두면 시작할 수 있어요."; band.visibility = View.VISIBLE }
        }
        addBtn(right, start, 24)
        right.addView(note("shield", "지하 주차장 같은 막힌 곳에서는 쓰지 마세요.").first, lp(MATCH, WRAP).apply { topMargin = dp(32) })
        if (Remind.spoke && Remind.lastLine.isNotEmpty())
            right.addView(note("speech", "달리는 중에 “${Remind.lastLine}”라고 권했어요.").first, lp(MATCH, WRAP).apply { topMargin = dp(16) })

        var doorOpen = false
        var lastRead = 0L
        fun update() {
            wake.text = wakeLine(pick * 60)
            val now = System.currentTimeMillis()
            if (now - lastRead > 2000) {
                lastRead = now
                Car.readSafety { open, _ -> if (doorOpen != (open == true)) { doorOpen = open == true; update() } }
            }
            when {
                doorOpen -> { bandText.text = "문이 열려 있어요. 닫으면 시작할 수 있어요."; band.visibility = View.VISIBLE }
                !Car.isPark -> { bandText.text = "기어를 P에 두면 시작할 수 있어요."; band.visibility = View.VISIBLE }
                else -> band.visibility = View.GONE
            }
            start.isEnabled = Car.isPark && !doorOpen
        }
        update()
        refresh = { update() }
        return frame(twoCol(left, right), back = true)
    }

    // ───── 재우는 중 ─────

    private fun fallingScreen(): View {
        val total = Nap.minutes * 60
        val left = panel()
        val ring = RingView(this, ticks = 6)
        val inner = innerCol()
        inner.addView(label("남은 시간", 28, Ui.W84, 500, 44, center = true))
        val clk = clock()
        inner.addView(clk.view, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        val wake = label("", 30, Ui.ACCENT, 700, 48, center = true)
        inner.addView(wake, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        left.addView(ringBox(ring, inner), lp(MATCH, dp(576)))
        val amb = valueText("")
        left.addView(panelRow("bulb", "무드등", "1분에 걸쳐 0%로 낮춰요", amb).view, lp(MATCH, WRAP).apply { topMargin = dp(16) })

        val right = column()
        right.addView(label("재우는 중", 26, Ui.W64, 400, 40))
        val doing = bigText("")
        right.addView(doing, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        val dots = StepDots(this, listOf("문", "빛", "소리", "공기"))
        right.addView(dots, lp(WRAP, WRAP).apply { topMargin = dp(56) })
        val (airNote, _) = note("air", "공조가 꺼져 있어 공기는 그대로 둬요.")
        airNote.visibility = View.GONE
        right.addView(airNote, lp(MATCH, WRAP).apply { topMargin = dp(32) })

        var hvac: Boolean? = null
        Car.hvacPower.get { v -> hvac = v; airNote.visibility = if (v == false) View.VISIBLE else View.GONE }
        var lastRead = 0L
        var first = true
        fun update() {
            val rem = Nap.remainingSec()
            val e = total - rem
            ring.set(if (total > 0) rem.toFloat() / total else 0f)
            clk.set("${ceil(rem / 60.0).toInt()}", "분")
            wake.text = wakeLine(rem)
            val (s, n) = when {
                e < 2 -> "쉬기 전 상태를 적어 뒀어요" to 0
                e < 3 -> "문을 잠갔어요" to 1
                e < 5 -> "빛을 끄고 있어요" to 2
                e < 20 -> "잠들 때의 소리를 틀었어요" to 4
                e < 40 && hvac != false -> "공기를 바꿨어요" to 4
                else -> "빛을 끄고 있어요" to 4
            }
            if (doing.text.toString() != s) { doing.text = s; if (!first) Ui.enter(doing) }
            dots.set(BooleanArray(4) { i -> i < n && !(i == 3 && hvac == false) }, animate = !first)
            first = false
            val now = System.currentTimeMillis()
            if (now - lastRead > 2000) { lastRead = now; Car.ambient.get { v -> if (v != null) amb.text = "$v%" } }
        }
        update()
        refresh = { update() }
        return frame(twoCol(left, right))
    }

    // ───── 자는 중: 검은 화면. 누르면 남은 시간과 '일찍 깼어요'가 5초 ─────

    private fun sleepScreen(): View {
        val f = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val hint = label("누르면 남은 시간이 보여요", 26, Ui.W50, 400, 40, center = true)
        f.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val peek = innerCol().apply { visibility = View.GONE }
        val total = Nap.minutes * 60
        val ring = RingView(this, ticks = 6)
        val inner = innerCol()
        inner.addView(label("남은 시간", 28, Ui.W84, 500, 44, center = true))
        val clk = clock()
        inner.addView(clk.view, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        peek.addView(ringBox(ring, inner), lp(dp(576), dp(576)))
        val wake = label("", 30, Ui.ACCENT, 700, 48, center = true)
        peek.addView(wake, lp(WRAP, WRAP).apply { topMargin = dp(32) })
        addBtn(peek, btn(Btn.Kind.TONAL, "일찍 깼어요", "sun") { Nap.restore("일찍 깼어요를 누름") }, 32, dp(400))
        f.addView(peek, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        var peekUntil = 0L
        fun update() {
            val rem = Nap.remainingSec()
            ring.set(if (total > 0) rem.toFloat() / total else 0f)
            clk.set(String.format(java.util.Locale.US, "%d:%02d", rem / 60, rem % 60), "")
            wake.text = wakeLine(rem)
            if (peek.visibility == View.VISIBLE && System.currentTimeMillis() >= peekUntil) { peek.visibility = View.GONE; hint.visibility = View.VISIBLE }
        }
        f.setOnClickListener {
            peekUntil = System.currentTimeMillis() + 5000
            if (peek.visibility != View.VISIBLE) { hint.visibility = View.GONE; peek.visibility = View.VISIBLE; Ui.enter(peek) }
            update()
        }
        update()
        refresh = { update() }
        return f
    }

    // ───── 깨우는 중: 네 단계로 밝아짐 ─────

    private fun wakeScreen(): View {
        val total = Nap.minutes * 60
        val left = panel()
        val ring = RingView(this, ticks = 0, segments = 4)
        val inner = innerCol()
        val cap = label("일어날 시간까지", 28, Ui.W84, 500, 44, center = true)
        val clk = clock()
        val sun = IconView(this, "sun", Ui.ACCENT)
        val slept = label("${Nap.minutes}분 쉬었어요", 30, Ui.ACCENT, 700, 48, center = true)
        inner.addView(cap); inner.addView(clk.view, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        inner.addView(sun, lp(dp(96), dp(96))); inner.addView(slept, lp(WRAP, WRAP).apply { topMargin = dp(16) })
        left.addView(ringBox(ring, inner), lp(MATCH, dp(576)))
        val amb = valueText("")
        left.addView(panelRow("bulb", "무드등", "단계로 밝아져요", amb).view, lp(MATCH, WRAP).apply { topMargin = dp(16) })

        val right = column()
        val title = bigText("")
        right.addView(title)
        val icons = row(Gravity.TOP)
        val circles = ArrayList<FrameLayout>(); val iviews = ArrayList<IconView>()
        listOf("bulb" to "빛", "fan" to "바람", "temp" to "온도", "note" to "소리").forEachIndexed { i, (ic, name) ->
            val col = innerCol()
            val circle = FrameLayout(this).apply { background = oval(Ui.PANEL, 2, Ui.TRACK) }
            val iv = IconView(this, ic, Ui.W40)
            circle.addView(iv, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            col.addView(circle, lp(dp(88), dp(88)))
            col.addView(label(name, 24, Ui.W64, 600, 32, center = true), lp(WRAP, WRAP).apply { topMargin = dp(12) })
            icons.addView(col, lp(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(32) })
            circles.add(circle); iviews.add(iv)
        }
        right.addView(icons, lp(WRAP, WRAP).apply { topMargin = dp(48) })
        val doing = label("", 30, Ui.W84, 500, 48)
        right.addView(doing, lp(MATCH, dp(96)).apply { topMargin = dp(24) })
        addBtn(right, btn(Btn.Kind.PRIMARY, "일어났어요", "check") { Nap.restore("일어났어요를 누름") }, 32)
        val (noteRow, noteText) = note("speech", "")
        right.addView(noteRow, lp(MATCH, WRAP).apply { topMargin = dp(32) })

        val page = frame(twoCol(left, right))
        var hvac: Boolean? = null
        Car.hvacPower.get { v -> hvac = v }
        var stage = 0
        var litIcons = 0
        var wakeAt = 0L
        var lastRead = 0L
        var bgNow = Ui.WAKE_BG[0]
        page.setBackgroundColor(bgNow)
        fun update() {
            val rem = Nap.remainingSec()
            val st = when { rem > 60 -> 1; rem > 10 -> 2; rem > 0 -> 3; else -> 4 }
            if (st == 4 && wakeAt == 0L) wakeAt = System.currentTimeMillis()
            val over = if (wakeAt == 0L) 0.0 else (System.currentTimeMillis() - wakeAt) * (if (Nap.fast) 30.0 else 1.0) / 1000
            if (st != stage) {
                val animate = stage != 0
                stage = st
                val target = Ui.WAKE_BG[st]
                if (animate) ValueAnimator.ofArgb(bgNow, target).apply { duration = 300; interpolator = Ui.easeOut(); addUpdateListener { page.setBackgroundColor(it.animatedValue as Int) } }.start()
                else page.setBackgroundColor(target)
                bgNow = target
                if (st >= 2) { left.background = rounded(Ui.WAKE_PANEL, 32); ring.trackColor = Ui.WAKE_TRACK; ring.panelColor = Ui.WAKE_PANEL }
                ring.setLit(st)
                title.text = if (st < 4) "곧 일어날 시간이에요" else "일어날 시간이에요"
                val n = when (st) { 1 -> 1; 2 -> 3; else -> 4 }
                for (i in litIcons until n) {
                    circles[i].background = oval(Ui.ACCENT); iviews[i].set(color = Ui.ON_PRIMARY)
                    if (animate) Ui.pop(circles[i])
                }
                litIcons = n
                doing.text = when (st) {
                    1 -> "빛을 천천히 밝히고 있어요"
                    2 -> if (hvac == false) "시트 열선을 껐어요. 공조가 꺼져 있어 바람과 온도는 그대로 둬요" else "시트 열선을 끄고, 온도를 낮추고 바람 3단계로 바꿨어요"
                    3 -> "깨우는 소리를 틀었어요"
                    else -> "“${Nap.minutes}분 쉬었어요. 일어날 시간이에요.”"
                }
                if (animate) Ui.enter(doing)
            }
            val showClock = st < 4
            cap.visibility = if (showClock) View.VISIBLE else View.GONE
            clk.view.visibility = if (showClock) View.VISIBLE else View.GONE
            sun.visibility = if (showClock) View.GONE else View.VISIBLE
            slept.visibility = sun.visibility
            clk.set(String.format(java.util.Locale.US, "%d:%02d", rem / 60, rem % 60), "")
            noteText.text = if (over < 60) "대답이 없으면 1분마다 다시 말하고, 3분째에는 실내등을 켜고 마쳐요." else "1분마다 다시 말해요. 세 번째에는 실내등을 켜고 마쳐요."
            val now = System.currentTimeMillis()
            if (now - lastRead > 2000) { lastRead = now; Car.ambient.get { v -> if (v != null) amb.text = "$v%" } }
            if (total <= 0) return
        }
        update()
        refresh = { update() }
        return page
    }

    // ───── 되돌린 화면(안전 되돌림 포함) ─────

    private fun cellName(dev: String) = when (dev) {
        "무드등 밝기", "무드등 색", "무드등 켜기" -> "무드등"
        "실내등", "독서등" -> "실내등"
        "공조 순환" -> "공기"
        "공조 풍량" -> "바람"
        "공조 온도" -> "온도"
        "시트 열선" -> "시트"
        else -> dev
    }

    private fun cellIcon(name: String) = when (name) {
        "무드등" -> "bulb"; "실내등" -> "cabin"; "공기" -> "air"; "바람" -> "fan"; "온도" -> "temp"; "시트" -> "seat"; "문 잠금" -> "lock"; "소리" -> "note"; else -> "restore"
    }

    private fun restoredSig() = "${Nap.state}|${Nap.restored}|${Nap.failed}|${Nap.retried}|${Nap.kept}"

    private fun restoredScreen(): View {
        val sig = restoredSig()
        val done = Nap.state == State.RESTORED

        val left = panel()
        val ring = RingView(this, ticks = 0).apply { set(1f) }
        val inner = innerCol()
        inner.addView(IconView(this, if (done) "check" else "restore", Ui.ACCENT), lp(dp(96), dp(96)))
        inner.addView(label(if (done) "${lastSleptMin()}분 쉬었어요" else "돌려놓는 중이에요", 40, Ui.WHITE, 800, 64, center = true), lp(WRAP, WRAP).apply { topMargin = dp(16) })
        left.addView(ringBox(ring, inner), lp(MATCH, dp(576)))
        left.addView(panelRow("calendar", "이번 주", weekLine(prefix = false), null).view, lp(MATCH, WRAP).apply { topMargin = dp(16) })

        val right = column()
        // 알림 띠: 왜 쪽잠을 마쳤는지(안전 되돌림), 문을 잠근 채로 둔 것
        val reason = Nap.endReason
        val bands = ArrayList<Pair<String, String>>()
        when {
            reason.contains("문이 열림") -> bands.add("door" to "문이 열려서 쪽잠을 마쳤어요.")
            reason.contains("기어") -> bands.add("gear" to "기어가 P를 벗어나 쪽잠을 마쳤어요.")
            reason.contains("운전석") -> bands.add("seat" to "운전석이 비어 쪽잠을 마쳤어요.")
            reason.contains("3분") && Nap.kept.isEmpty() -> bands.add("cabin" to "대답이 없어 실내등을 켜고 쪽잠을 마쳤어요.")
        }
        if (Nap.kept.isNotEmpty()) bands.add("lock" to "깨운 뒤 답이 없어 문은 잠근 채로 두었어요.")
        bands.forEach { (ic, s) -> right.addView(alertBand(ic, s).first, lp(MATCH, WRAP).apply { bottomMargin = dp(16) }) }
        val failedNames = Nap.failed.map { cellName(it) }.distinct()
        val title = when {
            !done -> "쉬기 전으로\n돌려놓는 중이에요"
            failedNames.isNotEmpty() -> josa(failedNames.joinToString(", "), "을", "를") + "\n직접 확인해 주세요"
            else -> "완전히 깬 뒤에\n출발하세요"
        }
        right.addView(bigText(title), lp(MATCH, WRAP).apply { if (bands.isNotEmpty()) topMargin = dp(16) })
        right.addView(label(if (failedNames.isNotEmpty() && done) "나머지는 쉬기 전으로 돌려놨어요" else "쉬기 전으로 돌려놓은 것", 28, Ui.W84, 600, 44), lp(MATCH, WRAP).apply { topMargin = dp(48) })

        // 아이콘 칸(4열): 돌려놓은 것, P에서 마저 돌려놓은 것, 못 돌린 것, 잠근 채로 둔 것, 소리
        val cells = LinkedHashMap<String, Pair<String, Boolean>>()
        Nap.restored.forEach { cells[cellName(it)] = "돌려놨어요" to false }
        Nap.retried.forEach { cells[cellName(it)] = "P에서 돌려놨어요" to false }
        Nap.kept.forEach { cells[cellName(it)] = "잠근 채로 뒀어요" to false }
        Nap.failed.forEach { cells[cellName(it)] = "돌려놓지 못했어요" to true }
        cells["소리"] = "껐어요" to false
        val grid = column()
        var r: LinearLayout? = null
        cells.entries.forEachIndexed { i, (name, v) ->
            if (i % 4 == 0) { r = row(Gravity.TOP); grid.addView(r, lp(MATCH, WRAP).apply { if (i > 0) topMargin = dp(16) }) }
            val cell = innerCol().apply {
                background = if (v.second) rounded(Ui.PANEL, 24, 2, Ui.ALERT) else rounded(Ui.PANEL, 24)
                setPadding(dp(8), dp(24), dp(8), dp(24))
            }
            val circle = FrameLayout(this).apply { background = oval(Ui.TRACK) }
            circle.addView(IconView(this, cellIcon(name), Ui.WHITE), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            cell.addView(circle, lp(dp(64), dp(64)))
            cell.addView(label(name, 24, Ui.WHITE, 700, 32, center = true), lp(WRAP, WRAP).apply { topMargin = dp(8) })
            cell.addView(label(v.first, 20, if (v.second) Ui.ALERT else Ui.W64, 400, 32, center = true), lp(WRAP, WRAP))
            r!!.addView(cell, lp(0, WRAP, 1f).apply { if (i % 4 > 0) leftMargin = dp(16) })
        }
        val pad = (4 - cells.size % 4) % 4
        repeat(pad) { r?.addView(View(this), lp(0, 1, 1f).apply { leftMargin = dp(16) }) }
        right.addView(grid, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        if (Nap.kept.isNotEmpty() && done) addBtn(right, btn(Btn.Kind.TONAL, "문 잠금 풀기", "lock") { Nap.unlockKept() }, 32)
        if (done) addBtn(right, btn(Btn.Kind.PRIMARY, if (failedNames.isNotEmpty()) "확인했어요" else "처음 화면으로", if (failedNames.isNotEmpty()) "check" else null) {
            Nap.finish(); go("home")
        }, if (Nap.kept.isNotEmpty()) 16 else 32)

        refresh = { if (restoredSig() != sig && screen == "restored") show("restored", animate = false) }
        return frame(twoCol(left, right))
    }

    // ───── 시연 도구 판 ─────

    private fun openTools() {
        if (tools != null) return
        val scrim = FrameLayout(this).apply { setBackgroundColor(Ui.SCRIM); isClickable = true }
        val p = column().apply {
            background = rounded(Ui.PANEL, 32, 1, Ui.W10)
            setPadding(dp(48), dp(48), dp(48), dp(48))
            isClickable = true
        }
        p.addView(label("시연 도구", 40, Ui.WHITE, 800, 64))
        p.addView(label("심사 시연에서 기다리는 시간을 줄이는 조작이에요", 26, Ui.W64, 400, 40), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        fun line(title: String, sub: String, right: View?): TextView {
            val wrap = column()
            wrap.addView(View(this).apply { setBackgroundColor(Ui.W10) }, lp(MATCH, 1))
            val r = row()
            r.minimumHeight = dp(120)
            val texts = column()
            texts.addView(label(title, 32, Ui.WHITE, 700, 48))
            val s = label(sub, 26, Ui.W64, 400, 40)
            texts.addView(s)
            r.addView(texts, lp(0, WRAP, 1f))
            right?.let { r.addView(it, lp(WRAP, if (it is Btn) dp(72) else WRAP).apply { leftMargin = dp(24) }) }
            wrap.addView(r, lp(MATCH, WRAP))
            p.addView(wrap, lp(MATCH, WRAP).apply { topMargin = dp(16) })
            return s
        }
        val fastToggle = Toggle(this, Nap.fast) { on -> Nap.fast = on; prefs.edit().putBoolean("fast", on).apply(); fastDot.visibility = if (on) View.VISIBLE else View.GONE }
        line("빠르게 보기", "1분을 2초로 줄여 보여 줘요", fastToggle)
        val hintSub = line("권하는 말 들어 보기", "두 시간 달렸을 때 하는 말을 지금 들려줘요", Btn(this, Btn.Kind.SMALL, "들어 보기").onClick { tryHint() })
        val weekSub = line("쉰 기록 지우기", weekLine(), Btn(this, Btn.Kind.SMALL, "지우기").onClick { Records.clear(); toolsRefresh?.invoke() })
        line("물음 다시 답하기", "처음 물음 셋을 다시 보여 줘요", Btn(this, Btn.Kind.SMALL, "다시 답하기").onClick { closeTools(); if (Nap.state == State.IDLE) go("ask") })
        val nums = label("", 26, Ui.W64, 400, 40).apply { fontFeatureSettings = "tnum" }
        val nums2 = label("", 26, Ui.W64, 400, 40).apply { fontFeatureSettings = "tnum" }
        val wrap = column()
        wrap.addView(View(this).apply { setBackgroundColor(Ui.W10) }, lp(MATCH, 1))
        wrap.addView(nums, lp(MATCH, WRAP).apply { topMargin = dp(24) })
        wrap.addView(nums2, lp(MATCH, WRAP))
        p.addView(wrap, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        addBtn(p, btn(Btn.Kind.PRIMARY, "닫기") { closeTools() }, 32)
        scrim.addView(p, FrameLayout.LayoutParams(dp(960), WRAP, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        tools = scrim
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        Ui.enter(p)
        toolsRefresh = {
            fastToggle.set(Nap.fast)
            weekSub.text = weekLine()
            if (hintText.isNotEmpty()) hintSub.text = hintText
            val d = Remind.drivenMin.toInt()
            val th = Remind.thresholdMin(if (Remind.on) Remind.q1 else prefs.getInt("q1", -1))
            nums.text = "달린 시간 ${if (d >= 60) "${d / 60}시간 ${d % 60}분" else "${d}분"}, 알리는 때 ${if (th % 60 == 0) "${th / 60}시간" else "${th / 60}시간 ${th % 60}분"}" +
                (if (Remind.on) "" else " (쉼 알림 꺼짐)")
            nums2.text = "내기 순환으로 ${Remind.recircMin.toInt()}분(기준 ${Remind.RECIRC_MIN}분), 이번 주행에 ${Remind.spokenCount}번 말함" +
                (if (Remind.signal.isNotEmpty()) ", 말하기 신호: ${Remind.signal}" else "")
        }
        toolsRefresh?.invoke()
    }

    private fun closeTools() {
        tools?.let { root.removeView(it) }
        tools = null
        toolsRefresh = null
        if (screen == "home") refresh?.invoke()
    }

    /** 이번 주행의 달린 시간을 읽고, 처음 물음의 첫째 답에 맞춘 문장을 차의 말로 들려줍니다(시연 도구 판). */
    private fun tryHint() {
        hintText = "달린 시간을 읽는 중이에요."
        toolsRefresh?.invoke()
        Car.tripMinutes { m ->
            val q1 = prefs.getInt("q1", -1)
            val line = "${phrase(Remind.baseMin(q1))} 달렸으니 다음 쉼터에서 쉬어 가요."
            val trip = when {
                m != null -> "${m}분"
                Car.tripError.contains("NOT_SUPPORTED") -> "에뮬레이터가 값을 주지 않아요(쉼 알림은 기어 신호로 직접 셈)"
                else -> "읽지 못했어요"
            }
            hintText = "“$line” 이번 주행 달린 시간: $trip"
            toolsRefresh?.invoke()
            Voice.say(line)
            main.postDelayed({ log("권하는 말 상태: ${Voice.status}") }, 6000)
        }
    }
}

/** 소개 화면의 걸음 그림 160: 빈 길 원(r64, 굵기 12) + 남빛 그림(1 호와 바늘, 2 달, 3 해) */
class StepPic(ctx: Context, private val step: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(c: Canvas) {
        val s = width / 160f
        c.save(); c.scale(s, s)
        p.strokeWidth = 12f; p.color = Ui.TRACK
        c.drawCircle(80f, 80f, 64f, p)
        when (step) {
            1 -> {
                p.color = Ui.ACCENT
                c.drawArc(RectF(16f, 16f, 144f, 144f), -90f, 252f, false, p)
                p.color = Ui.WHITE; p.strokeWidth = 6f
                c.drawLine(80f, 80f, 80f, 48f, p); c.drawLine(80f, 80f, 102f, 92f, p)
            }
            else -> {
                p.color = Ui.ACCENT; p.strokeWidth = 2f
                c.save(); c.translate(44f, 44f); c.scale(3f, 3f)
                Icons.draw(c, if (step == 2) "moon" else "sun", p)
                c.restore()
            }
        }
        c.restore()
    }
}
