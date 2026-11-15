package kr.carlarm.app

import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class State { IDLE, FALLING, ASLEEP, WAKING, RESTORING, RESTORED }

/**
 * 쪽잠 한 바퀴의 상태 기계와 재우는 차례, 깨우는 차례, 되돌리기.
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
 *
 * 0.4(2026-11-15 밤):
 * - 시트 편한 자세: 등받이, 높이, 앞뒤, 쿠션 기울기와 길이, 허리 받침, 머리 받침을 한 번에 하나씩, 위치를 읽으며 움직이고 목표에서 멈춥니다(Car.moveTo).
 *   뒷자리에 사람이나 짐이 있으면(또는 운전자가 '있어요'라고 답하면) 등받이 눕히기와 뒤로 밀기는 하지 않습니다.
 * - 창 잠금, 노을빛 무드등, 바람 방향(발 쪽), 공기청정, 블라인드와 스마트 글라스(달린 차만), 추울 때 시트 열선.
 * - 깨울 때: 추우면 핸들과 사이드미러 열선(T−2분), 바람 방향 얼굴 쪽과 김 서림 없애기(T−1분), 시트 통풍과 시트 세우기(T−30초), 창 잠금 풀고 창 조금 열기(T).
 * - 되돌리기는 0.4에서 더한 장치까지 모두. 시트와 창은 기어가 P일 때만 움직입니다. P를 벗어났는데 등받이가 누워 있으면 "등받이를 세우고 출발하세요"를 말합니다.
 * - 쓸수록 맞추기(Learn): 쉬는 자세, 쉬는 방(온도와 바람), 권하는 때. 차 안에만 적고 지우는 버튼이 있습니다.
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
    val kept = ArrayList<String>()         // 일부러 돌려놓지 않고 둔 장치(깨운 뒤 3분째의 문 잠금, 실내등)
    private val failedDevs = ArrayList<Dev<*>>()
    private var retrying = false
    var onChange: (() -> Unit)? = null

    private const val POLL_MS = 2000L      // 문 열림 알림이 오지 않을 때의 받침, 운전석 빔 확인
    /** 노을빛(주황) 무드등 색. 값은 가정 */
    const val SUNSET = 0xFF8A3D

    private var token = Any()
    private var t0 = 0L                    // 차례의 0초(uptime)
    private var wallT0 = 0L                // 차례의 0초(벽시계). 되살릴 때 씁니다
    private var startedWall = 0L           // '시작'을 누른 때(벽시계). 쉰 시간에 씁니다
    private var wakeStyle = 1
    private var hvacOn = false
    private var driverInAtStart = false
    /** 뒷자리에 사람이나 짐이 있음(읽은 값 또는 운전자의 답). 등받이 눕히기와 뒤로 밀기를 하지 않습니다 */
    var rearOccupied = false; private set
    /** 시작할 때 읽은 실내 온도(℃). 못 읽으면 null */
    var cabinAtStart: Float? = null; private set
    private val cold get() = (cabinAtStart ?: 99f) < 18f   // 추울 때(가정 18℃)
    private var heatOnByUs = false
    private var blindMoved = false
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

    // ───── 시트 모터 차례: 한 번에 하나씩(새 차례가 오면 지금 움직이는 축을 마친 뒤 새 차례로) ─────

    private var motorList: List<Pair<String, (() -> Unit) -> Unit>> = emptyList()
    var motorBusy = false; private set
    var motorNow = ""; private set
    private fun motors(list: List<Pair<Dev<Int>, Int>>, label: String) {
        log("시트 차례($label): " + list.joinToString { "${it.first.name}→${it.second}" })
        jobs(list.map { (d, v) -> d.name to { done: () -> Unit -> Car.apply(d, v, tries = 1) { done() } } })
    }
    private fun jobs(list: List<Pair<String, (() -> Unit) -> Unit>>) {
        motorList = list
        if (!motorBusy) motorNext()
    }
    private fun motorNext() {
        if (motorList.isEmpty()) { motorBusy = false; motorNow = ""; changed(); return }
        motorBusy = true
        val (name, job) = motorList[0]
        motorList = motorList.drop(1)
        motorNow = name
        changed()
        job { motorNext() }
    }

    /**
     * 시트 위치를 모름: 열 축을 모두 못 읽었거나 모두 0으로 읽힘(에뮬레이터에서 봄. 실제 차에서 열 축이 모두 0일 일은 드물다고 보고 가름).
     * 이때는 정한 시간만큼 보내고 멈추며(Car.pushFor), 되돌릴 때 반대로 같은 시간만큼 보낸 뒤 '직접 확인해 주세요'를 보입니다.
     */
    var posUnknown = false; private set
    /** 위치를 모른 채 세우거나 되돌린 축(되돌린 화면에 '직접 확인'으로 보임) */
    private val unconfirmed = LinkedHashSet<String>()
    /** 위치를 모를 때 쉬는 자세로 보낼 방향과 시간(ms). 값은 가정: 에뮬레이터 콘솔의 시트 모터가 1초에 50만큼 움직여(100ms에 5),
     *  60에서 등받이를 30쯤 눕히고 끝에 닿지 않을 만큼으로 정함. 끝에 닿으면 시간으로 되돌릴 때 반대쪽으로 지나침 */
    private fun timedPose(ax: Car.Axis): Pair<ai.pleos.playground.vehicle.constant.control.MoveControl, Long> {
        val B = ai.pleos.playground.vehicle.constant.control.MoveControl.BACKWARD
        val F = ai.pleos.playground.vehicle.constant.control.MoveControl.FORWARD
        return when (ax) {
            Car.backrest -> B to 600L     // 눕히기
            Car.height -> B to 300L       // 조금 낮추기
            Car.foreAft -> B to 400L      // 뒤로
            Car.lumbar -> B to 200L       // 허리 받침 풀기
            else -> F to 200L             // 쿠션 기울기와 길이, 옆 받침, 머리 받침
        }
    }

    // ───── 시작: 0초에 쉬기 전 값을 적어 두고, 재우는 차례를 겁니다 ─────

    /** rear: 뒷자리에 사람이나 짐이 있음(쪽잠 준비 화면이 읽은 값이나 운전자의 답) */
    fun start(min: Int, wake: Int, rear: Boolean = false): Boolean {
        if (active || state == State.RESTORING) return false
        if (!Car.isPark) { log("기어가 P가 아니라 시작하지 않음(${Car.gearName()})"); return false }
        minutes = min
        wakeStyle = wake
        rearOccupied = rear
        Car.bump()
        token = Any()
        resumeFromSec = -1.0
        restored.clear(); failed.clear(); retried.clear(); kept.clear(); failedDevs.clear(); before.clear(); endReason = ""; caption = ""
        heatOnByUs = false; blindMoved = false; cabinAtStart = null
        Car.axes.forEach { it.blindMs = 0 }
        unconfirmed.clear()
        t0 = SystemClock.uptimeMillis()
        wallT0 = System.currentTimeMillis()
        startedWall = wallT0
        state = State.FALLING
        if (Remind.on) Learn.addDriven(Remind.drivenMin.toInt())
        CarlarmService.sync()
        changed()
        val my = token
        snapshot(Car.all) {
            if (my !== token || state != State.FALLING) return@snapshot
            hvacOn = before[Car.hvacPower] == true
            log("쉬기 전 값(${before.size}개): " + before.entries.joinToString { "${it.key.name}=${it.value}" })
            posUnknown = Car.seats.all { (before[it] as? Int ?: 0) == 0 }
            if (posUnknown) log("시트 위치를 모름(열 축 모두 0이거나 못 읽음): 정한 시간만큼 보내고 멈춤(시간은 가정)")
            Car.readSafety { _, driverIn ->
                if (my !== token || state != State.FALLING) return@readSafety
                driverInAtStart = driverIn == true
                log("시작 때 운전석 탑승=$driverIn (앉아 있던 경우에만 '자리가 빔'으로 되돌림), 뒷자리 있음=$rearOccupied")
                Car.cabinTemp { t ->
                    if (my !== token || state != State.FALLING) return@cabinTemp
                    cabinAtStart = t
                    log("시작 때 실내 온도 $t ℃ (추울 때 기준 18℃, 가정)")
                    schedule()
                }
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

    /** 잠들 때 공조 온도와 바람: 기억한 쉬는 방 → 실내 온도로 정한 값 → 0.3.1처럼 +1℃, 바람 1(값은 가정) */
    private fun roomPlan(): Pair<Float?, Int> {
        val mem = Learn.room()
        if (mem != null) return mem.first.coerceIn(Car.tempMin, Car.tempMax) to mem.second
        val t = was(Car.temp)
        val c = cabinAtStart
        val dt = when { c == null -> 1f; c < 22f -> 1f; c > 26f -> -1f; else -> 0f }
        val fan = if (c != null && c > 26f) 2 else 1
        return t?.let { (it + dt).coerceIn(Car.tempMin, Car.tempMax) } to fan
    }

    /** 쉬는 자세의 목표(축 → 위치). 기억한 자세가 있으면 그것, 없으면 쉬기 전 위치에서 정한 기본 자세(가정) */
    private fun restPoseTimed(): List<Pair<String, (() -> Unit) -> Unit>> = Car.axes.mapNotNull { ax ->
        if (rearOccupied && (ax === Car.backrest || ax === Car.foreAft)) { log("뒷자리 있음: ${ax.name}는 움직이지 않음"); return@mapNotNull null }
        val (dir, ms) = timedPose(ax)
        ax.name to { done: () -> Unit -> Car.pushFor(ax, dir, ms) { done() } }
    }

    private fun restPose(): List<Pair<Dev<Int>, Int>> {
        val mem = Learn.pose()
        val out = ArrayList<Pair<Dev<Int>, Int>>()
        Car.axes.forEachIndexed { i, ax ->
            val d = Car.seats[i]
            val b = was(d) ?: return@forEachIndexed
            if (rearOccupied && (ax === Car.backrest || ax === Car.foreAft)) { log("뒷자리 있음: ${ax.name}는 움직이지 않음"); return@forEachIndexed }
            val span = (ax.max - ax.min).coerceAtLeast(1)
            fun rel(p: Double) = (b + span * p).roundToInt()
            val target = mem[ax.key] ?: when (ax) {
                Car.backrest -> rel(-0.30)    // 눕히기(작을수록 눕힘. AAOS SEAT_BACKREST_ANGLE_1_POS의 최소가 가장 눕힌 자리)
                Car.height -> rel(-0.10)      // 조금 낮추기
                Car.foreAft -> rel(-0.20)     // 뒤로(최소가 가장 뒤)
                Car.tilt -> rel(0.10)         // 쿠션 앞을 조금 올림
                Car.depth -> rel(0.10)        // 쿠션 길게
                Car.lumbar -> rel(-0.30)      // 허리 받침 풀기
                Car.lumbarSide -> rel(0.30)   // 옆 받침 넓게(덜 조임)
                Car.headAngle -> rel(0.10)
                Car.headHeight -> rel(0.10)
                else -> rel(0.10)             // 머리 받침 앞뒤
            }
            val tgt = if (ax.known) target.coerceIn(ax.min, ax.max) else target
            if (kotlin.math.abs(tgt - b) > ax.tol) out.add(d to tgt)
        }
        return out
    }

    private fun schedule() {
        if (resumeFromSec < 0) { t0 = SystemClock.uptimeMillis(); wallT0 = System.currentTimeMillis() }
        save()
        val T = minutes * 60.0
        // 받침 알람: 앱이 죽어 있어도 다음 깨울 때(깨우기 시작, 이미 지났으면 깨운 뒤 3분)에 시스템이 서비스를 깨움
        val nextSec = if (resumeFromSec < T - leadSec()) T - leadSec() else T + 181.0
        CarlarmService.setBackstop(wallT0 + ms(nextSec))
        // 0초: 안전 문장
        at(0.0) { say("도로 위나 갓길, 지하 주차장 같은 막힌 곳에서는 쓰지 마세요. ${minutes}분 뒤에 깨울게요.") }
        // 2초: 문을 잠금(네 문 묶음), 창 잠금. 다시 읽어 확인합니다.
        at(2.0) {
            if (was(Car.doorLock) == false) Car.apply(Car.doorLock, true) { ok -> if (!ok) log("문 잠금이 다시 읽기에서 확인되지 않음") }
            if (was(Car.windowLock) == false) Car.apply(Car.windowLock, true) { ok -> if (!ok) log("창 잠금이 다시 읽기에서 확인되지 않음") }
        }
        // 3초: 실내등과 독서등을 끔, 무드등을 노을빛으로
        at(3.0) {
            if (was(Car.cabin) == LightSwitch.ON) Car.apply(Car.cabin, LightSwitch.OFF)
            if (was(Car.reading) == LightSwitch.ON) Car.apply(Car.reading, LightSwitch.OFF)
            if (was(Car.ambientColor) != null && was(Car.ambientColor) != SUNSET) Car.apply(Car.ambientColor, SUNSET)
        }
        // 3초~63초: 무드등을 1분에 걸쳐 0%로
        val startB = was(Car.ambient)
        if (startB != null) fade(startB, 0, 3.0, 63.0)
        at(63.5) {
            if (startB != null) Car.apply(Car.ambient, 0) { ok -> if (!ok) log("무드등 0%가 다시 읽기에서 확인되지 않음") }
            if (state == State.FALLING) { state = State.ASLEEP; changed() }
        }
        // 5초: 공조가 켜져 있으면 바깥 공기, 바람과 온도(기억한 쉬는 방 또는 실내 온도로 정한 값), 바람 방향 발 쪽, 공기청정
        at(5.0) {
            if (hvacOn) {
                val (t, f) = roomPlan()
                log("잠들 때 공조: 온도 $t, 바람 $f (${if (Learn.room() != null) "기억한 쉬는 방" else "실내 온도 $cabinAtStart ℃로 정함(가정)"})")
                if (was(Car.recirc) == true) Car.apply(Car.recirc, false)
                if (was(Car.fan) != null) Car.apply(Car.fan, f)
                if (t != null && was(Car.temp) != null) Car.apply(Car.temp, t)
                val floor = listOf(FanDirectionType.FLOOR, FanDirectionType.FACE_AND_FLOOR).firstOrNull { Car.dirOptions.isEmpty() || it in Car.dirOptions }
                if (was(Car.fanDir) != null && floor != null && was(Car.fanDir) != floor) Car.apply(Car.fanDir, floor)
            }
            if (was(Car.airCleaner) == false) Car.apply(Car.airCleaner, true)
            // 추울 때(실내 18℃ 아래, 가정) 시트 열선 1단계
            if (cold && (was(Car.seatHeat) ?: 1) == 0) { heatOnByUs = true; Car.apply(Car.seatHeat, 1) }
        }
        // 5초: 입면 소리. 5분 뒤부터 줄여 8분에 끔(가정)
        at(5.0) { Sound.play(Sound.SLEEP, 0.5f) }
        for (i in 0..10) at(300.0 + i * 18.0) { if (state == State.FALLING || state == State.ASLEEP) Sound.gain = 0.5f * (10 - i) / 10f }
        at(481.0) { if (state == State.ASLEEP) Sound.stop() }
        // 6초: 블라인드 닫기, 스마트 글라스 흐리게(달린 차만, 지원하지 않으면 건너뜀)
        at(6.0) {
            Car.blind(true) { r -> log(r); if (r.startsWith("블라인드 BACKWARD")) blindMoved = true }
            if (Car.glassOk == true && was(Car.smartGlass) == false) Car.apply(Car.smartGlass, true)
            else log("스마트 글라스: ${if (Car.glassOk != true) "지원 안 함, 건너뜀" else "쉬기 전 값 ${was(Car.smartGlass)}라 그대로"}")
        }
        // 8초: 시트 편한 자세(한 번에 하나씩, 위치를 읽으며 움직이고 목표에서 멈춤)
        at(8.0) {
            if (!active) return@at
            if (posUnknown) { val j = restPoseTimed(); log("시트 차례(위치를 모름, 정한 시간): " + j.joinToString { it.first }); jobs(j); return@at }
            val pose = restPose()
            if (pose.isEmpty()) log("시트 편한 자세: 움직일 축 없음(위치를 읽지 못했거나 이미 그 자리)")
            else motors(pose, if (Learn.pose().isEmpty()) "기본 자세(가정)" else "기억한 자세")
        }

        // 깨우는 차례. 처음 물음의 셋째 답: 0 빛부터 천천히(T−2분), 1 바람과 함께(기본, T−1분 30초), 2 소리로 바로(T−30초)
        val lead = leadSec()
        val soundAt = wakeSoundSec()
        // 깨우기 직전: 쉬는 동안의 온도와 바람을 기억(쉬는 방 맞추기)
        at(T - lead - 1) { if (hvacOn) Car.temp.get { t -> Car.fan.get { f -> Learn.saveRoom(t, f) } } }
        at(T - lead) {
            state = State.WAKING
            Sound.stop()
            changed()
            CarlarmService.wakeAlert(true)   // 칼람 화면이 앞에 없으면 '일어났어요' 버튼이 달린 알림
        }
        // T−2분: 추울 때 핸들과 사이드미러 열선
        at(T - 120.0) {
            if (cold) {
                if (was(Car.wheelHeat) == 0) Car.apply(Car.wheelHeat, 1)
                if (was(Car.mirrorHeat) == 0) Car.apply(Car.mirrorHeat, 1)
            } else log("핸들과 사이드미러 열선: 춥지 않아(실내 $cabinAtStart ℃) 켜지 않음")
        }
        if (startB != null) {
            fade(0, 70, T - lead, T)
            at(T + 0.5) { Car.apply(Car.ambient, 70) }
        }
        at(T - 60.0) {
            // 시트 열선은 공조 전원과 상관없이 켜져 있으면 끔(기획서 칸 3의 표)
            if ((was(Car.seatHeat) ?: 0) > 0 || heatOnByUs) Car.apply(Car.seatHeat, 0)
            if (hvacOn) {
                was(Car.temp)?.let { Car.apply(Car.temp, (it - 3f).coerceIn(Car.tempMin, Car.tempMax)) }
                if (was(Car.fan) != null) Car.apply(Car.fan, 3)
                if (was(Car.fanDir) != null && (Car.dirOptions.isEmpty() || FanDirectionType.FACE in Car.dirOptions)) Car.apply(Car.fanDir, FanDirectionType.FACE)
            }
            if (was(Car.defrost) == false) Car.apply(Car.defrost, true)
        }
        // T−30초: 시트 통풍 3단계, 쉬는 자세를 기억하고 운전하던 자세 쪽으로 천천히 세우기 시작
        at(T - 30.0) {
            if (was(Car.seatVent) != null) Car.apply(Car.seatVent, 3)
            val pose = HashMap<String, Int>()
            fun readAll(i: Int) {
                if (i >= Car.axes.size) {
                    if (pose.isNotEmpty()) Learn.savePose(pose)
                    val up = Car.seats.filter { before.containsKey(it) }.map { it to was(it)!! }
                    if (up.isNotEmpty() && active) motors(up, "운전하던 자세로 세우기")
                    return
                }
                if (!before.containsKey(Car.seats[i])) return readAll(i + 1)
                Car.readAxis(Car.axes[i]) { v -> if (v != null) pose[Car.axes[i].key] = v; readAll(i + 1) }
            }
            fun raiseUnknown() {
                log("쓸수록 맞추기: 시트 위치를 몰라 쉬는 자세는 기억하지 않음")
                val j = Car.axes.filter { it.blindMs != 0L }.map { ax -> ax.name to { done: () -> Unit -> Car.unblind(ax) { _ -> unconfirmed.add(ax.name); done() } } }
                if (j.isNotEmpty() && active) { log("시트 차례(운전하던 자세로 세우기, 보낸 시간만큼 반대로): " + j.joinToString { it.first }); jobs(j) }
            }
            val start = { if (posUnknown) raiseUnknown() else readAll(0) }
            if (!motorBusy) start() else { motorList = emptyList(); fun waitIdle() { if (motorBusy) main.postDelayed({ waitIdle() }, 200) else start() }; waitIdle() }
        }
        at(T - soundAt) { Sound.play(Sound.WAKE, 0.15f) }
        for (i in 1..5) at(T - soundAt + i * soundAt / 5) { if (state == State.WAKING) Sound.gain = 0.15f + 0.09f * i }
        val wakeLine = "${minutes}분 쉬었어요. 일어날 시간이에요."
        at(T) {
            say(wakeLine); CarlarmService.wakeAlert(true)
            // 창 잠금을 풀고 앞 창을 조금 엶(바깥 공기). 조금 = 범위의 5분의 1(가정)
            if (was(Car.windowLock) == false) Car.apply(Car.windowLock, false)
            val crack = Car.winMin + (Car.winMax - Car.winMin) / 5
            main.postDelayed({
                if (!active || !Car.isPark) return@postDelayed
                listOf(Car.winFL, Car.winFR).forEach { w -> val b = was(w); if (b != null && b < crack) Car.apply(w, crack) }
            }, 2000)
        }
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

    // ───── 되돌리기 ─────

    /**
     * keepLock: 깨운 뒤 3분째처럼 사람이 답하지 않았을 때는, 칼람이 잠근 문을 잠근 채로 두고 나머지만 되돌립니다.
     * 그때 켠 실내등도 켠 채로 둡니다(0.3.1은 켜자마자 되돌리기가 쉬기 전 값으로 다시 꺼 버렸음).
     * 사람이 '일어났어요'를 누르거나 문을 열거나 기어를 옮기면 문 잠금까지 되돌립니다.
     */
    fun restore(reason: String, keepLock: Boolean = false) {
        if (!active) return
        log("되돌림 시작: $reason")
        main.removeCallbacksAndMessages(token)
        token = Any()
        Car.bump()          // 앞 차례의 늦은 다시 읽기와 재시도는 이제 버립니다
        motorList = emptyList()
        Car.unwatchDoors()
        CarlarmService.wakeAlert(false)
        CarlarmService.clearBackstop()
        Sound.stop()
        endReason = reason
        state = State.RESTORING
        val slept = sleptSec()
        changed()
        // 처음 값을 읽은 장치만, 지금 값과 다르면 돌려놓고 다시 읽어 확인합니다.
        var order = (listOf<Dev<*>>(Car.doorLock, Car.windowLock) + Car.windows + listOf(Car.ambientSwitch, Car.ambientColor, Car.ambient, Car.cabin, Car.reading,
            Car.recirc, Car.fan, Car.temp, Car.fanDir, Car.airCleaner, Car.defrost, Car.seatHeat, Car.seatVent, Car.wheelHeat, Car.mirrorHeat, Car.smartGlass) + Car.seats)
            .filter { before.containsKey(it) }
        if (keepLock && was(Car.doorLock) == false) {
            order = order.filter { it !== Car.doorLock }
            kept.add(Car.doorLock.name)
            log("문 잠금은 잠근 채로 둠(깨운 뒤 답이 없음)")
        }
        if (keepLock && reason.contains("3분") && was(Car.cabin) == LightSwitch.OFF) {
            // 독서등을 끄면 실내등도 함께 꺼져(에뮬레이터 콘솔에서 봄) 독서등도 그대로 둡니다.
            order = order.filter { it !== Car.cabin && it !== Car.reading }
            kept.add(Car.cabin.name)
            log("실내등과 독서등은 켠 채로 둠(깨운 뒤 답이 없음)")
        }
        // P를 벗어났는데 등받이가 쉬기 전보다 누워 있으면 말로 알림(시트는 P가 아니면 움직이지 않음)
        val backB = was(Car.seatOf(Car.backrest))
        var sayUp = false
        fun proceed() {
            back(order) { unblindAll {
                if (blindMoved) { if (Car.isPark) Car.blind(false) { r -> log("되돌림: $r") } else { log("블라인드: 기어 ${Car.gearName()}라 열지 않음"); failed.add("블라인드") } }
                state = State.RESTORED
                clearSaved()
                Records.add(slept, reason)
                Remind.resetTrip()
                say(when {
                    sayUp -> "등받이를 세우고 출발하세요."
                    kept.isEmpty() -> "완전히 깬 뒤에 출발하세요."
                    kept.contains(Car.doorLock.name) -> "문은 잠근 채로 두었어요. 완전히 깬 뒤에 출발하세요."
                    else -> "완전히 깬 뒤에 출발하세요."
                })
                log("되돌림 끝: 돌려놓음=$restored, 못 돌림=$failed, 그대로 둠=$kept, 쉰 시간 ${slept}초")
                if (Car.isPark) retryAtPark()
                CarlarmService.sync()
            } }
        }
        // 움직이던 축이 멈출 때까지 기다린 뒤(P를 벗어나면 바로 멈춤) 등받이를 봅니다.
        fun go() {
            if (motorBusy) { main.postDelayed({ go() }, 100); return }
            if (!Car.isPark && (backB != null || Car.backrest.blindMs != 0L)) {
                Car.readAxis(Car.backrest) { now ->
                    log("기어 ${Car.gearName()}: 등받이 지금 $now, 쉬기 전 $backB, 위치를 모른 채 보낸 시간 ${Car.backrest.blindMs}ms")
                    if ((now != null && backB != null && now < backB - Car.backrest.tol) || Car.backrest.blindMs < 0) { sayUp = true; say("등받이를 세우고 출발하세요.") }
                    proceed()
                }
            } else proceed()
        }
        go()
    }

    @Suppress("UNCHECKED_CAST")
    private fun back(devs: List<Dev<*>>, done: () -> Unit) {
        if (devs.isEmpty()) return done()
        val d = devs[0] as Dev<Any>
        val want = before[d]!!
        // 시트와 창은 기어가 P일 때만 움직입니다(명세 0절). 창은 P에 서면 마저 돌려놓고, 시트는 운전자가 직접 확인하도록 이름을 보입니다.
        val isSeat = Car.seats.any { it === d }
        val isWin = Car.windows.any { it === d }
        val pOnly = isSeat || isWin
        if (pOnly && !Car.isPark) {
            val blind = isSeat && Car.axes[Car.seats.indexOfFirst { it === d }].blindMs != 0L
            d.get { now ->
                if (!blind && now != null && d.same(now, want)) return@get back(devs.drop(1), done)
                log("${d.name}: 기어 ${Car.gearName()}라 움직이지 않음(지금 $now, 쉬기 전 $want${if (blind) ", 위치를 못 읽은 채 움직인 축" else ""})")
                failed.add(d.name)
                if (isWin) failedDevs.add(d)
                changed()
                back(devs.drop(1), done)
            }
            return
        }
        if (isSeat && Car.axes[Car.seats.indexOfFirst { it === d }].blindMs != 0L) return back(devs.drop(1), done)   // unblindAll이 맡음
        d.get { now ->
            if (now != null && d.same(now, want)) {
                log("${d.name}: 지금 $now, 쉬기 전 $want 와 같아 그대로")
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

    /** 위치를 모른 채 움직인 시트 축: P면 보낸 시간만큼 반대로 보내고, P가 아니면 움직이지 않습니다. 둘 다 '직접 확인'으로 보입니다. */
    private fun unblindAll(done: () -> Unit) {
        val list = Car.axes.filter { it.blindMs != 0L }
        fun next(i: Int) {
            if (i >= list.size) {
                unconfirmed.forEach { if (!failed.contains(it)) failed.add(it) }
                changed(); return done()
            }
            val ax = list[i]
            if (!Car.isPark) { log("${ax.name}: 기어 ${Car.gearName()}라 움직이지 않음(위치를 모른 채 ${ax.blindMs}ms 보낸 축)"); if (!failed.contains(ax.name)) failed.add(ax.name); return next(i + 1) }
            Car.unblind(ax) { _ -> if (!failed.contains(ax.name)) failed.add(ax.name); changed(); next(i + 1) }
        }
        next(0)
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
        is FanDirectionType -> JSONObject().put("t", "fd").put("v", v.name)
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Int -> JSONObject().put("t", "i").put("v", v)
        else -> JSONObject().put("t", "?")
    }

    private fun dec(o: JSONObject): Any? = when (o.optString("t")) {
        "ls" -> try { LightSwitch.valueOf(o.getString("v")) } catch (_: Exception) { null }
        "fd" -> try { FanDirectionType.valueOf(o.getString("v")) } catch (_: Exception) { null }
        "b" -> o.getBoolean("v")
        "f" -> o.getDouble("v").toFloat()
        "i" -> o.getInt("v")
        else -> null
    }

    private fun save() {
        val b = JSONObject()
        before.forEach { (d, v) -> b.put(d.name, enc(v)) }
        val o = JSONObject().put("minutes", minutes).put("wake", wakeStyle).put("fast", fast)
            .put("wallT0", wallT0).put("startedWall", startedWall).put("hvacOn", hvacOn).put("driverIn", driverInAtStart)
            .put("rear", rearOccupied).put("heatByUs", heatOnByUs).put("before", b)
        cabinAtStart?.let { o.put("cabin", it.toDouble()) }
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
        rearOccupied = o.optBoolean("rear")
        heatOnByUs = o.optBoolean("heatByUs")
        cabinAtStart = if (o.has("cabin")) o.optDouble("cabin").toFloat() else null
        blindMoved = false
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

/**
 * 쓸수록 맞추기(0.4). 차 안(SharedPreferences)에만 적고, 쪽잠 준비 화면과 시연 도구의 '지우기'로 지웁니다.
 * - 쉬는 자세: 깨우기 전(T−30초)에 읽은 시트 위치(쉬는 동안 운전자가 직접 바꾼 것까지). 다음 쪽잠의 목표 자세
 * - 쉬는 방: 깨우기 직전에 읽은 공조 온도와 바람. 다음 쪽잠에 처음부터 씀
 * - 권하는 때: 쉬기로 한 때(쪽잠 시작)의 달린 시간. 다음에는 그 평균에 권하고 120분보다 늦추지 않음
 */
object Learn {
    private val p get() = Records.prefs

    fun pose(): Map<String, Int> = try {
        val o = JSONObject(p.getString("learnPose", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getInt(it) }
    } catch (_: Exception) { emptyMap() }

    fun savePose(m: Map<String, Int>) {
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString("learnPose", o.toString()).apply()
        log("쓸수록 맞추기: 쉬는 자세 기억 $m")
    }

    fun room(): Pair<Float, Int>? = try {
        p.getString("learnRoom", null)?.let { JSONObject(it) }?.let { it.getDouble("t").toFloat() to it.getInt("fan") }
    } catch (_: Exception) { null }

    fun saveRoom(t: Float?, fan: Int?) {
        if (t == null || fan == null) { log("쓸수록 맞추기: 쉬는 방을 읽지 못해 기억하지 않음(온도 $t, 바람 $fan)"); return }
        p.edit().putString("learnRoom", JSONObject().put("t", t.toDouble()).put("fan", fan).toString()).apply()
        log("쓸수록 맞추기: 쉬는 방 기억 온도 $t, 바람 $fan")
    }

    /** 쉬기로 한 때의 달린 시간(분). 10분보다 짧으면 적지 않습니다(가정). 최근 다섯 번만 둡니다 */
    fun addDriven(min: Int) {
        if (min < 10) { log("쓸수록 맞추기: 달린 시간 ${min}분은 짧아 적지 않음"); return }
        val a = try { JSONArray(p.getString("learnDriven", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(min)
        val keep = JSONArray(); for (i in maxOf(0, a.length() - 5) until a.length()) keep.put(a.getInt(i))
        p.edit().putString("learnDriven", keep.toString()).apply()
        log("쓸수록 맞추기: 쉬기로 한 때의 달린 시간 ${min}분 적음(최근 ${keep.length()}번)")
    }

    /** 최근 쉬기로 한 때의 달린 시간 평균(분, 30~120). 기록이 없으면 null */
    fun avgDriven(): Int? {
        val a = try { JSONArray(p.getString("learnDriven", "[]")) } catch (_: Exception) { JSONArray() }
        if (a.length() == 0) return null
        val avg = (0 until a.length()).sumOf { a.getInt(it) } / a.length().toDouble()
        return avg.roundToInt().coerceIn(30, 120)
    }

    fun has() = pose().isNotEmpty() || room() != null || avgDriven() != null

    fun clear() {
        p.edit().remove("learnPose").remove("learnRoom").remove("learnDriven").apply()
        log("쓸수록 맞추기: 기억한 값을 지움")
    }

    /** 시연 도구에 보일 한 줄 */
    fun line(): String {
        val parts = ArrayList<String>()
        if (pose().isNotEmpty()) parts.add("쉬는 자세 ${pose().size}곳")
        room()?.let { parts.add("쉬는 방 ${it.first}℃, 바람 ${it.second}") }
        avgDriven()?.let { parts.add("권하는 때 ${it}분") }
        return if (parts.isEmpty()) "아직 맞춘 값이 없어요" else parts.joinToString(", ")
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
}
