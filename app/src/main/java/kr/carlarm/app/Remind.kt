package kr.carlarm.app

import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar

/**
 * 쉼 알림(설계 4절 A, 기획서 칸 3 '때를 안다').
 *
 * 차가 운전자의 졸음을 직접 판단한 값은 일반 앱이 읽을 수 없습니다(AAOS DRIVER_DROWSINESS_ATTENTION_STATE는
 * Signature|Privileged 권한, Pleos의 DMS_DROWSINESS_WARNING은 공개되지 않은 값, 경고등 목록은 제한 권한).
 * 그래서 칼람은 졸음이 오기 쉬운 때를 차의 신호로 셉니다.
 *  ① 쉼 없이 달린 시간: 기어가 P가 아닌 시간을 AAOS 기어 신호로 셉니다. 기준은 처음 물음의 첫째 답(60, 90, 120분).
 *     밤 10시~새벽 5시와 낮 1시~3시에는 90분을 넘기지 않습니다(값은 가정).
 *  ② 내기 순환으로 달린 시간: 내기 순환 알림을 받아, 순환이 켜진 채 달린 시간이 40분이 되면 환기를 권합니다
 *     (소방청 수칙 "30~40분에 한 번씩 창문을 열어 환기"와 짝).
 *  권한 뒤 쉬지 않고 30분을 더 달리면, 처음 물음의 둘째 답에 따라 환기나 음악을 권하는 말 한 문장만 건넵니다(한 번).
 *  P에 15분 넘게 서 있거나 쪽잠을 마치면 처음부터 다시 셉니다. 말은 한 주행(쉼 알림을 켠 뒤)에 세 번까지입니다.
 *  달리는 중에는 화면을 띄우지 않고 차 장치를 바꾸지 않으며, 차의 말 한 문장만 합니다.
 *
 * 0.2(2026-11-15 오후): 앞에서 도는 서비스가 프로세스를 지켜, 다른 앱을 앞에 띄워도 셉니다.
 * 앱이 죽었다가 10분 안에 다시 켜지면 센 값을 이어 갑니다(꺼져 있던 동안은 기어를 몰라 세지 못함).
 */
object Remind {
    var on = false; private set
    var q1 = -1; private set
    var q2 = 0; private set
    var drivenMin = 0.0; private set      // 쉼 없이 달린 시간(분, 빠르게 보기면 30배)
    var recircMin = 0.0; private set      // 그중 내기 순환으로 달린 시간
    var spoke = false; private set        // 이번 쉼 구간에 권했는가(P에 서면 쪽잠 준비를 띄움)
    var spokeAtMin = 0; private set
    var spokenCount = 0; private set      // 이번 주행에 한 말 수(세 번까지)
    var lastLine = ""; private set
    var signal = ""; private set          // 말하기 신호(시작과 끝)가 왔는가
    var prepShown = false
    var onTick: (() -> Unit)? = null
    /** 켜짐, 꺼짐, 되살림, 말함처럼 화면이 바뀌어야 할 때 부릅니다(화면이 버튼 글자를 다시 그리는 데 씀) */
    var onChange: (() -> Unit)? = null

    const val RECIRC_MIN = 40
    const val NUDGE_AFTER_MIN = 30
    const val MAX_LINES = 3
    const val REST_PARK_MIN = 15

    private var last = 0L
    private var parkMin = 0.0
    var driveSaid = false; private set       // 이번 쉼 구간에 달린 시간으로 권했는가
    var recircSaid = false; private set      // 이번 쉼 구간에 환기를 권했는가(화면의 '바깥 공기로 바꾸기'에 씀)
    private var sinceSaidMin = -1.0       // 처음 권한 뒤 달린 시간(-1이면 아직 권하지 않음)
    private var nudged = false
    var lastSignal = ""; private set         // 마지막으로 권한 신호: "drive" 또는 "recirc"
    private var token = Any()
    private var ticks = 0

    fun baseMin(q: Int = q1) = when (q) { 0 -> 60; 1 -> 90; else -> 120 }
    fun riskyHour(h: Int) = h >= 22 || h < 5 || h == 13 || h == 14
    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    /** 지금 시각에 맞춘 달린 시간 기준(분) */
    fun thresholdMin(q: Int = q1): Int = if (riskyHour(hour())) minOf(baseMin(q), 90) else baseMin(q)
    private fun phrase(min: Int) = when (min) { 60 -> "한 시간"; 90 -> "한 시간 반"; else -> "두 시간" }

    fun start(q1: Int, q2: Int) {
        if (on || !Car.isPark) return
        this.q1 = q1
        this.q2 = q2
        on = true
        spokenCount = 0
        rested()
        token = Any()
        last = SystemClock.uptimeMillis()
        Car.refreshRecirc()
        log("쉼 알림 켬(첫째 답 $q1, 둘째 답 비트 $q2, 달린 시간 기준 ${thresholdMin()}분, 지금 ${hour()}시, 환기 ${RECIRC_MIN}분, 한 주행 ${MAX_LINES}번까지)")
        save()
        CarlarmService.sync()
        loop(token)
        onChange?.invoke()
    }

    fun stop() {
        on = false
        token = Any()
        clearSaved()
        log("쉼 알림 끔")
        CarlarmService.sync()
        onChange?.invoke()
    }

    /** 쪽잠을 마치면(쉬고 나면) 처음부터 다시 셉니다. */
    fun resetTrip() = rested()

    private fun rested() {
        drivenMin = 0.0; recircMin = 0.0; parkMin = 0.0
        driveSaid = false; recircSaid = false; sinceSaidMin = -1.0; nudged = false; lastSignal = ""
        spoke = false; prepShown = false; signal = ""
    }

    // 켤 때마다 새 표(token)를 써서, 껐다 곧바로 켜도 1초 반복이 둘로 늘지 않게 합니다.
    private fun loop(my: Any) {
        if (my !== token || !on) return
        tick()
        main.postDelayed({ loop(my) }, 1000)
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val m = (now - last) * (if (Nap.fast) 30 else 1) / 60000.0
        last = now
        val driving = Car.gear != null && !Car.isPark
        if (driving && ticks % 15 == 0) Car.refreshRecirc()   // 순환 알림을 놓쳤을 때를 위한 받침
        if (driving) {
            drivenMin += m
            parkMin = 0.0
            if (Car.recircOn == true) recircMin += m
            if (sinceSaidMin >= 0) sinceSaidMin += m
        } else {
            parkMin += m
            if (parkMin > REST_PARK_MIN && !Nap.active && (drivenMin > 0 || spoke)) { log("P에 ${REST_PARK_MIN}분 넘게 서 있어 센 시간을 0으로"); rested() }
        }
        if (driving && spokenCount < MAX_LINES) {
            val th = thresholdMin()
            when {
                !driveSaid && drivenMin >= th -> {
                    driveSaid = true; lastSignal = "drive"
                    speak("${phrase(th)} 달렸으니 다음 쉼터에서 쉬어 가요.", "달린 시간 ${drivenMin.toInt()}분, 기준 ${th}분")
                }
                !recircSaid && recircMin >= RECIRC_MIN -> {
                    recircSaid = true; lastSignal = "recirc"
                    speak("내기 순환으로 ${RECIRC_MIN}분 달렸으니 바깥 공기로 바꾸거나 쉬어 가요.", "내기 순환으로 달린 시간 ${recircMin.toInt()}분")
                }
                !nudged && sinceSaidMin >= NUDGE_AFTER_MIN -> {
                    nudged = true
                    speak(nudgeLine(), "권한 뒤 쉬지 않고 ${sinceSaidMin.toInt()}분")
                }
            }
        }
        if (++ticks % 10 == 0) save()
        onTick?.invoke()
    }

    /** 처음 물음의 둘째 답(비트: 0 잠깐 자기, 1 바깥 공기, 2 음악, 3 잘 모르겠어요)에 따라 환기나 음악 */
    private fun nudgeLine(): String {
        val air = (q2 and 2) != 0
        val music = (q2 and 4) != 0
        val useMusic = music && (!air || lastSignal == "recirc")
        return if (useMusic) "글레오에게 음악을 틀어 달라고 해 보세요." else "바깥 공기로 바꿔 보세요."
    }

    private fun speak(line: String, why: String) {
        spoke = true
        spokeAtMin = drivenMin.toInt()
        spokenCount++
        lastLine = line
        if (sinceSaidMin < 0) sinceSaidMin = 0.0
        log("쉼 알림: $why, 기어 ${Car.gearName()}, 이번 주행 ${spokenCount}번째 말: $line")
        Voice.say(line)
        onChange?.invoke()
        main.postDelayed({ signal = if (Voice.status.contains("onDone")) "시작과 끝 신호가 왔어요" else Voice.status; log("쉼 알림 말하기 신호: $signal") }, 6000)
        save()
    }

    // ───── 센 값 적어 두기와 되살리기 ─────

    fun hasSaved() = Records.prefs.contains("remind")

    private fun save() {
        if (!on) return
        val o = JSONObject().put("q1", q1).put("q2", q2).put("driven", drivenMin).put("recirc", recircMin).put("park", parkMin)
            .put("count", spokenCount).put("driveSaid", driveSaid).put("recircSaid", recircSaid).put("since", sinceSaidMin)
            .put("nudged", nudged).put("last", lastSignal).put("spoke", spoke).put("spokeAt", spokeAtMin).put("wall", System.currentTimeMillis())
        Records.prefs.edit().putString("remind", o.toString()).apply()
    }

    private fun clearSaved() = Records.prefs.edit().remove("remind").apply()

    /** 앱이 죽었다가 다시 켜졌을 때 서비스가 부릅니다. 10분 넘게 지난 값은 버립니다(차를 껐다 켠 것으로 봄). */
    fun recover() {
        if (on) return
        val s = Records.prefs.getString("remind", null) ?: return
        val o = try { JSONObject(s) } catch (_: Exception) { clearSaved(); return }
        if (System.currentTimeMillis() - o.optLong("wall") > 10 * 60 * 1000L) { log("쉼 알림: 적어 둔 값이 10분 넘게 지나 버림"); clearSaved(); return }
        q1 = o.optInt("q1", -1); q2 = o.optInt("q2", 0)
        drivenMin = o.optDouble("driven", 0.0); recircMin = o.optDouble("recirc", 0.0); parkMin = o.optDouble("park", 0.0)
        spokenCount = o.optInt("count", 0); driveSaid = o.optBoolean("driveSaid"); recircSaid = o.optBoolean("recircSaid")
        sinceSaidMin = o.optDouble("since", -1.0); nudged = o.optBoolean("nudged"); lastSignal = o.optString("last", "")
        spoke = o.optBoolean("spoke"); spokeAtMin = o.optInt("spokeAt", 0)
        on = true
        token = Any()
        last = SystemClock.uptimeMillis()
        log("쉼 알림 되살림(센 시간 ${drivenMin.toInt()}분, 내기 순환 ${recircMin.toInt()}분, 이번 주행 말 ${spokenCount}번)")
        loop(token)
        onChange?.invoke()
    }
}
