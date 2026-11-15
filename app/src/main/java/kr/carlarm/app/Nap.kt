package kr.carlarm.app

import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class State { IDLE, FALLING, ASLEEP, WAKING, RESTORING, RESTORED }

/**
 * 쪽잠 한 바퀴의 상태 기계(설계 3절)와 재우는 차례, 깨우는 차례, 되돌리기(설계 4절 B, C, D의 Must 줄).
 * 화면과 떨어져 앱 프로세스에서 돕니다. 화면이 가려져도(주행 제한) 되돌리기는 여기서 바로 일어납니다.
 *
 * 0.2(2026-11-15 오후)에서 바뀐 것:
 * - 쪽잠 동안 앞에서 도는 서비스(CarlarmService)가 앱 프로세스를 지킵니다. 앱 화면이 가려지거나 다른 앱을 써도 차례가 이어집니다.
 * - 쉬기 전 값과 시작 시각을 차 안(SharedPreferences)에 적어 둡니다. 앱이 죽었다가 다시 켜지면 지난 차례는 건너뛰고 남은 차례부터 잇습니다.
 * - 깨운 뒤 3분째에는 문 잠금을 잠근 채로 두고 나머지만 되돌립니다(답이 없는 사람이 잠금 풀린 차에 남지 않게).
 * - 되돌리기를 시작하면 차례 번호(Car.epoch)를 올려, 앞 차례의 늦은 재시도가 되돌린 값을 다시 바꾸지 않게 합니다.
 * - 시작하자마자 끝나도 쉰 시간이 바르게 적힙니다(시작 시각을 '시작'을 누른 때 적음).
 * - 시트 열선은 공조 전원과 상관없이 T−1분에 끕니다(기획서 칸 3의 표).
 * - 문 열림은 알림(registerDoorPosition)으로 받고, 2초마다 읽기는 알림이 오지 않을 때를 위한 받침으로 남깁니다.
 */
object Nap {
    var state = State.IDLE; private set
    var minutes = 20; private set
    var fast = false
    var caption = ""; private set          // 마지막으로 차가 한 말(화면에도 띄움)
    var endReason = ""; private set
    val restored = ArrayList<String>()     // 쉬기 전 값으로 돌려놓은 장치
    val failed = ArrayList<String>()       // 돌려놓지 못한 장치
    val retried = ArrayList<String>()      // 다음에 P에 서서 마저 돌려놓은 장치
    val kept = ArrayList<String>()         // 일부러 돌려놓지 않고 둔 장치(깨운 뒤 3분째의 문 잠금)
    private val failedDevs = ArrayList<Dev<*>>()
    private var retrying = false
    var onChange: (() -> Unit)? = null

    private const val POLL_MS = 2000L      // 문 열림 알림이 오지 않을 때의 받침, 운전석 빔 확인

    private var token = Any()
    private var t0 = 0L                    // 차례의 0초(uptime)
    private var wallT0 = 0L                // 차례의 0초(벽시계). 되살릴 때 씁니다
    private var startedWall = 0L           // '시작'을 누른 때(벽시계). 쉰 시간에 씁니다
    private var wakeStyle = 1
    private var hvacOn = false
    private var driverInAtStart = false
    private val before = LinkedHashMap<Dev<*>, Any>()
    private var resumeFromSec = -1.0       // 되살릴 때: 이 초보다 앞의 차례는 이미 지나간 것이라 건너뜀

    /** 빠르게 보기: 1분을 2초로(30배) */
    private val mult get() = if (fast) 30.0 else 1.0
    private fun ms(sec: Double): Long = (sec * 1000 / mult).toLong()
    private fun at(sec: Double, block: () -> Unit) {
        if (resumeFromSec >= 0 && sec < resumeFromSec) return
        main.postAtTime({ block() }, token, t0 + ms(sec))
    }
    private fun changed() = onChange?.invoke()
    val active get() = state == State.FALLING || state == State.ASLEEP || state == State.WAKING
    /** 자는 중 화면에 보일 남은 시간(초, 실제 분 기준) */
    fun remainingSec(): Int {
        val elapsed = (SystemClock.uptimeMillis() - t0) * mult / 1000
        return (minutes * 60 - elapsed).toInt().coerceAtLeast(0)
    }
    private fun sleptSec(): Int = ((System.currentTimeMillis() - startedWall) * mult / 1000).toInt().coerceAtLeast(0)
    private fun leadSec() = when (wakeStyle) { 0 -> 120.0; 2 -> 30.0; else -> 90.0 }
    private fun wakeSoundSec() = if (wakeStyle == 2) 30.0 else 10.0

    private fun say(text: String) {
        caption = text
        Voice.say(text)
        changed()
    }

    // ───── 시작: 0초에 쉬기 전 값을 적어 두고, 재우는 차례를 겁니다 ─────

    fun start(min: Int, wake: Int): Boolean {
        if (active || state == State.RESTORING) return false
        if (!Car.isPark) { log("기어가 P가 아니라 시작하지 않음(${Car.gearName()})"); return false }
        minutes = min
        wakeStyle = wake
        Car.bump()
        token = Any()
        resumeFromSec = -1.0
        restored.clear(); failed.clear(); retried.clear(); kept.clear(); failedDevs.clear(); before.clear(); endReason = ""; caption = ""
        t0 = SystemClock.uptimeMillis()
        wallT0 = System.currentTimeMillis()
        startedWall = wallT0
        state = State.FALLING
        CarlarmService.sync()
        changed()
        val my = token
        snapshot(Car.all) {
            if (my !== token || state != State.FALLING) return@snapshot
            hvacOn = before[Car.hvacPower] == true
            log("쉬기 전 값: " + before.entries.joinToString { "${it.key.name}=${it.value}" })
            Car.readSafety { _, driverIn ->
                if (my !== token || state != State.FALLING) return@readSafety
                driverInAtStart = driverIn == true
                log("시작 때 운전석 탑승=$driverIn (앉아 있던 경우에만 '자리가 빔'으로 되돌림)")
                schedule()
            }
        }
        return true
    }

    private fun snapshot(devs: List<Dev<*>>, done: () -> Unit) {
        if (devs.isEmpty()) return done()
        @Suppress("UNCHECKED_CAST")
        val d = devs[0] as Dev<Any>
        d.get { v -> if (v != null) before[d] = v; snapshot(devs.drop(1), done) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> was(d: Dev<T>): T? = before[d] as T?

    private fun schedule() {
        if (resumeFromSec < 0) { t0 = SystemClock.uptimeMillis(); wallT0 = System.currentTimeMillis() }
        save()
        val T = minutes * 60.0
        // 받침 알람: 앱이 죽어 있어도 다음 깨울 때(깨우기 시작, 이미 지났으면 깨운 뒤 3분)에 시스템이 서비스를 깨움
        val nextSec = if (resumeFromSec < T - leadSec()) T - leadSec() else T + 181.0
        CarlarmService.setBackstop(wallT0 + ms(nextSec))
        // 0초: 안전 문장
        at(0.0) { say("지하 주차장 같은 막힌 곳에서는 쓰지 마세요. ${minutes}분 뒤에 깨울게요.") }
        // 2초: 문을 잠금(네 문 묶음). 다시 읽어 확인합니다.
        at(2.0) { if (was(Car.doorLock) == false) Car.apply(Car.doorLock, true) { ok -> if (!ok) log("문 잠금이 다시 읽기에서 확인되지 않음") } }
        // 3초: 실내등과 독서등을 끔
        at(3.0) {
            if (was(Car.cabin) == LightSwitch.ON) Car.apply(Car.cabin, LightSwitch.OFF)
            if (was(Car.reading) == LightSwitch.ON) Car.apply(Car.reading, LightSwitch.OFF)
        }
        // 3초~63초: 무드등을 1분에 걸쳐 0%로
        val startB = was(Car.ambient)
        if (startB != null) fade(startB, 0, 3.0, 63.0)
        at(63.5) {
            if (startB != null) Car.apply(Car.ambient, 0) { ok -> if (!ok) log("무드등 0%가 다시 읽기에서 확인되지 않음") }
            if (state == State.FALLING) { state = State.ASLEEP; changed() }
        }
        // 5초: 공조가 켜져 있으면 바깥 공기, 바람 1단계, 온도 +1℃(값은 가정)
        at(5.0) {
            if (hvacOn) {
                if (was(Car.recirc) == true) Car.apply(Car.recirc, false)
                if (was(Car.fan) != null) Car.apply(Car.fan, 1)
                was(Car.temp)?.let { Car.apply(Car.temp, (it + 1f).coerceIn(Car.tempMin, Car.tempMax)) }
            }
        }
        // 5초: 입면 소리. 5분 뒤부터 줄여 8분에 끔(가정)
        at(5.0) { Sound.play(Sound.SLEEP, 0.5f) }
        for (i in 0..10) at(300.0 + i * 18.0) { if (state == State.FALLING || state == State.ASLEEP) Sound.gain = 0.5f * (10 - i) / 10f }
        at(481.0) { if (state == State.ASLEEP) Sound.stop() }

        // 깨우는 차례. 처음 물음의 셋째 답: 0 빛부터 천천히(T−2분), 1 바람과 함께(기본, T−1분 30초), 2 소리로 바로(T−30초)
        val lead = leadSec()
        val soundAt = wakeSoundSec()
        at(T - lead) {
            state = State.WAKING
            Sound.stop()
            changed()
            CarlarmService.wakeAlert(true)   // 칼람 화면이 앞에 없으면 '일어났어요' 버튼이 달린 알림
        }
        if (startB != null) {
            fade(0, 70, T - lead, T)
            at(T + 0.5) { Car.apply(Car.ambient, 70) }
        }
        at(T - 60.0) {
            // 시트 열선은 공조 전원과 상관없이 켜져 있으면 끔(기획서 칸 3의 표)
            if ((was(Car.seatHeat) ?: 0) > 0) Car.apply(Car.seatHeat, 0)
            if (hvacOn) {
                was(Car.temp)?.let { Car.apply(Car.temp, (it - 3f).coerceIn(Car.tempMin, Car.tempMax)) }
                if (was(Car.fan) != null) Car.apply(Car.fan, 3)
            }
        }
        at(T - soundAt) { Sound.play(Sound.WAKE, 0.15f) }
        for (i in 1..5) at(T - soundAt + i * soundAt / 5) { if (state == State.WAKING) Sound.gain = 0.15f + 0.09f * i }
        val wakeLine = "${minutes}분 쉬었어요. 일어날 시간이에요."
        at(T) { say(wakeLine); CarlarmService.wakeAlert(true) }
        at(T + 60) { say(wakeLine); CarlarmService.wakeAlert(true) }
        at(T + 120) { say(wakeLine); CarlarmService.wakeAlert(true) }
        at(T + 180) {
            Car.send(Car.cabin, LightSwitch.ON)
            val my = token
            main.postDelayed({ if (my === token) restore("깨운 뒤 3분이 지남", keepLock = true) }, 1500)
        }
        watch()
    }

    /** from에서 to로 10번에 나눠 보냅니다. 끝 값은 따로 확인합니다. */
    private fun fade(from: Int, to: Int, s0: Double, s1: Double) {
        for (i in 1..10) at(s0 + (s1 - s0) * i / 10) {
            if (active) Car.send(Car.ambient, (from + (to - from) * i / 10.0).roundToInt())
        }
    }

    // ───── 안전: 문이 열림, 운전석이 빔, 기어가 P를 벗어남 → 바로 되돌림 ─────

    private fun watch() {
        val my = token
        // 문 열림은 알림으로 바로 받습니다.
        Car.watchDoors { if (my === token && active) restore("문이 열림") }
        // 알림이 오지 않을 때를 위한 받침과 운전석 빔 확인
        fun tick() {
            if (my !== token || !active) return
            if (Car.gear != null && !Car.isPark) return restore("기어가 P를 벗어남")
            Car.readSafety { doorOpen, driverIn ->
                if (my !== token || !active) return@readSafety
                when {
                    doorOpen == true -> restore("문이 열림")
                    driverInAtStart && driverIn == false -> restore("운전석이 빔")
                    else -> main.postDelayed({ tick() }, POLL_MS)
                }
            }
        }
        tick()
    }

    /** 기어 콜백에서 바로 부릅니다(화면이 없어도 Car가 부릅니다). */
    fun onGear() {
        if (active && Car.gear != null && !Car.isPark) restore("기어가 P를 벗어남")
        if (state == State.RESTORED && Car.isPark) retryAtPark()
    }

    /** 기어가 P가 아닐 때 거절된 장치를, 다시 P에 서면 한 번 더 쉬기 전 값으로 돌려놓습니다(다시 읽어 확인). */
    @Suppress("UNCHECKED_CAST")
    private fun retryAtPark() {
        if (retrying || failedDevs.isEmpty()) return
        retrying = true
        val list = ArrayList(failedDevs)
        log("P에 서서 마저 돌려놓기: ${list.map { it.name }}")
        fun next(i: Int) {
            if (i >= list.size) { retrying = false; changed(); return }
            val d = list[i] as Dev<Any>
            Car.apply(d, before[d]!!) { ok ->
                if (ok) { failedDevs.remove(d); failed.remove(d.name); retried.add(d.name) }
                log("P에서 ${d.name} ${if (ok) "돌려놓음" else "또 못 돌림"}")
                next(i + 1)
            }
        }
        next(0)
    }

    // ───── 되돌리기(설계 4절 D) ─────

    /**
     * keepLock: 깨운 뒤 3분째처럼 사람이 답하지 않았을 때는, 칼람이 잠근 문을 잠근 채로 두고 나머지만 되돌립니다.
     * 사람이 '일어났어요'를 누르거나 문을 열거나 기어를 옮기면 문 잠금까지 되돌립니다.
     */
    fun restore(reason: String, keepLock: Boolean = false) {
        if (!active) return
        log("되돌림 시작: $reason")
        main.removeCallbacksAndMessages(token)
        token = Any()
        Car.bump()          // 앞 차례의 늦은 다시 읽기와 재시도는 이제 버립니다
        Car.unwatchDoors()
        CarlarmService.wakeAlert(false)
        CarlarmService.clearBackstop()
        Sound.stop()
        endReason = reason
        state = State.RESTORING
        val slept = sleptSec()
        changed()
        // 처음 값을 읽은 장치만, 지금 값과 다르면 돌려놓고 다시 읽어 확인합니다.
        var order = listOf<Dev<*>>(Car.doorLock, Car.ambientSwitch, Car.ambientColor, Car.ambient, Car.cabin, Car.reading, Car.recirc, Car.fan, Car.temp, Car.seatHeat)
            .filter { before.containsKey(it) }
        if (keepLock && was(Car.doorLock) == false) {
            order = order.filter { it !== Car.doorLock }
            kept.add(Car.doorLock.name)
            log("문 잠금은 잠근 채로 둠(깨운 뒤 답이 없음)")
        }
        back(order) {
            state = State.RESTORED
            clearSaved()
            Records.add(slept, reason)
            Remind.resetTrip()
            say(if (kept.isEmpty()) "완전히 깬 뒤에 출발하세요." else "문은 잠근 채로 두었어요. 완전히 깬 뒤에 출발하세요.")
            log("되돌림 끝: 돌려놓음=$restored, 못 돌림=$failed, 그대로 둠=$kept, 쉰 시간 ${slept}초")
            if (Car.isPark) retryAtPark()
            CarlarmService.sync()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun back(devs: List<Dev<*>>, done: () -> Unit) {
        if (devs.isEmpty()) return done()
        val d = devs[0] as Dev<Any>
        val want = before[d]!!
        d.get { now ->
            if (now != null && d.same(now, want)) {
                back(devs.drop(1), done)
            } else {
                Car.apply(d, want) { ok ->
                    (if (ok) restored else failed).add(d.name)
                    if (!ok) failedDevs.add(d)
                    changed()
                    back(devs.drop(1), done)
                }
            }
        }
    }

    /** 되돌린 화면의 '문 잠금 풀기'. 잠근 채로 둔 문을 쉬기 전(풀림)으로 돌립니다. */
    fun unlockKept() {
        if (!kept.contains(Car.doorLock.name)) return
        Car.apply(Car.doorLock, false) { ok ->
            if (ok) { kept.remove(Car.doorLock.name); restored.add(Car.doorLock.name) }
            log("문 잠금 풀기 ${if (ok) "됨" else "다시 읽기에서 확인되지 않음"}")
            changed()
        }
    }

    fun finish() {
        if (state == State.RESTORED) { state = State.IDLE; changed() }
    }

    // ───── 쉬기 전 값 적어 두기와 되살리기 ─────

    private val prefs: SharedPreferences get() = Records.prefs

    private fun enc(v: Any): JSONObject = when (v) {
        is LightSwitch -> JSONObject().put("t", "ls").put("v", v.name)
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Int -> JSONObject().put("t", "i").put("v", v)
        else -> JSONObject().put("t", "?")
    }

    private fun dec(o: JSONObject): Any? = when (o.optString("t")) {
        "ls" -> try { LightSwitch.valueOf(o.getString("v")) } catch (_: Exception) { null }
        "b" -> o.getBoolean("v")
        "f" -> o.getDouble("v").toFloat()
        "i" -> o.getInt("v")
        else -> null
    }

    private fun save() {
        val b = JSONObject()
        before.forEach { (d, v) -> b.put(d.name, enc(v)) }
        val o = JSONObject().put("minutes", minutes).put("wake", wakeStyle).put("fast", fast)
            .put("wallT0", wallT0).put("startedWall", startedWall).put("hvacOn", hvacOn).put("driverIn", driverInAtStart).put("before", b)
        prefs.edit().putString("nap", o.toString()).commit()
    }

    private fun clearSaved() = prefs.edit().remove("nap").apply()
    fun hasSaved() = prefs.contains("nap")

    /**
     * 앱이 죽었다가 다시 켜졌을 때 서비스가 부릅니다. 차 장치는 앱이 죽어도 마지막 값을 지니고 있으므로,
     * 지난 차례는 건너뛰고 남은 차례(깨우기, 되돌리기)부터 잇습니다. 깨운 뒤 3분도 지났으면 바로 되돌립니다.
     */
    fun recover() {
        if (state != State.IDLE && state != State.RESTORED) return
        val s = prefs.getString("nap", null) ?: return
        val o = try { JSONObject(s) } catch (_: Exception) { clearSaved(); return }
        minutes = o.optInt("minutes", 20)
        wakeStyle = o.optInt("wake", 1)
        fast = o.optBoolean("fast", false)
        wallT0 = o.optLong("wallT0")
        startedWall = o.optLong("startedWall", wallT0)
        hvacOn = o.optBoolean("hvacOn")
        driverInAtStart = o.optBoolean("driverIn")
        restored.clear(); failed.clear(); retried.clear(); kept.clear(); failedDevs.clear(); before.clear(); endReason = ""; caption = ""
        val b = o.optJSONObject("before") ?: JSONObject()
        Car.all.forEach { d -> b.optJSONObject(d.name)?.let { dec(it) }?.let { before[d] = it } }
        val elapsedMs = (System.currentTimeMillis() - wallT0).coerceAtLeast(0)
        val el = elapsedMs * mult / 1000.0
        val T = minutes * 60.0
        Car.bump()
        token = Any()
        t0 = SystemClock.uptimeMillis() - elapsedMs
        state = when {
            el < 63.5 -> State.FALLING
            el < T - leadSec() -> State.ASLEEP
            else -> State.WAKING
        }
        log("되살림: 시작 뒤 ${el.toInt()}초(차례 기준), ${minutes}분 쪽잠, 쉬기 전 값 ${before.size}개, 상태 $state")
        if (el >= T + 180) {
            restore("앱이 다시 켜졌을 때 깨운 뒤 3분이 지나 있음", keepLock = true)
            return
        }
        changed()
        resumeFromSec = el
        schedule()
        resumeFromSec = -1.0
        if (state == State.WAKING && el >= T - wakeSoundSec()) Sound.play(Sound.WAKE, 0.6f)
        if (state == State.WAKING) CarlarmService.wakeAlert(true)
        if (state != State.WAKING) log("되살림: 입면 소리는 다시 켜지 않음(자는 사람을 놀라게 하지 않으려고)")
    }
}

/** 쉰 기록(쉰 때, 길이, 깬 차례). 차 안(SharedPreferences)에만 둡니다. */
object Records {
    lateinit var prefs: SharedPreferences
    fun init(ctx: Context) { if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("carlarm", Context.MODE_PRIVATE) }

    private fun all(): JSONArray = try { JSONArray(prefs.getString("records", "[]")) } catch (_: Exception) { JSONArray() }

    fun add(sleptSec: Int, how: String) {
        val a = all()
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("sec", sleptSec).put("min", Nap.minutes).put("how", how).put("fast", Nap.fast))
        prefs.edit().putString("records", a.toString()).apply()
    }

    fun clear() = prefs.edit().remove("records").apply()

    // 화면이 읽는 값(지난 7일)
    private fun week(): List<JSONObject> {
        val a = all()
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getLong("at") >= since }
    }
    /** 이번 주(지난 7일) 쉰 횟수 */
    fun weekCount(): Int = week().size
    /** 이번 주(지난 7일) 쉰 시간의 합(분) */
    fun weekMinutes(): Int = (week().sumOf { it.getInt("sec") } / 60.0).roundToInt()
    /** 지난번에 쉰 때(밀리초). 기록이 없으면 null */
    fun lastAt(): Long? = all().let { if (it.length() == 0) null else it.getJSONObject(it.length() - 1).getLong("at") }
    /** 지난번에 고른 쪽잠 길이(분). 0.2 앞의 기록에는 없어서 null */
    fun lastMinutes(): Int? = all().let { if (it.length() == 0) null else it.getJSONObject(it.length() - 1).optInt("min", 0).takeIf { m -> m > 0 } }

    /** "이번 주 쉼 2번, 35분" (지난 7일) */
    fun weekLine(): String {
        val a = all()
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        var n = 0
        var sec = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("at") >= since) { n++; sec += o.getInt("sec") }
        }
        if (n == 0) return "이번 주 쉼은 아직 없어요."
        val last = a.getJSONObject(a.length() - 1)
        val t = java.text.SimpleDateFormat("M월 d일 a h:mm", java.util.Locale.KOREA).format(java.util.Date(last.getLong("at")))
        return "이번 주 쉼 ${n}번, ${(sec / 60.0).roundToInt()}분. 지난번은 $t, ${(last.getInt("sec") / 60.0).roundToInt()}분, ${last.getString("how")}" +
            (if (last.optBoolean("fast")) "(빠르게 보기)" else "") + "."
    }
}
