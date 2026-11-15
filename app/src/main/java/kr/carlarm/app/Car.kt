package kr.carlarm.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

const val TAG = "Carlarm"
val main = Handler(Looper.getMainLooper())
fun log(msg: String) { Log.i(TAG, msg) }

/**
 * 차 장치 하나. get은 지금 값을(못 읽으면 null), set은 SDK가 성공이라 답했는지를 돌려줍니다.
 * SDK의 성공 답만으로는 값이 바뀌었다고 보지 않고, apply()에서 다시 읽어 확인합니다.
 */
class Dev<T : Any>(
    val name: String,
    val get: (cb: (T?) -> Unit) -> Unit,
    val set: (T, cb: (Boolean) -> Unit) -> Unit,
    val same: (T, T) -> Boolean = { a, b -> a == b },
)

/** 콜백을 한 번만, 메인 스레드에서 부릅니다. 3초 안에 답이 없으면 null(또는 false)로 부릅니다. */
private fun <T> once(fallback: T, timeoutMs: Long = 3000, cb: (T) -> Unit): (T) -> Unit {
    val done = AtomicBoolean(false)
    val f: (T) -> Unit = { v -> if (done.compareAndSet(false, true)) main.post { cb(v) } }
    main.postDelayed({ f(fallback) }, timeoutMs)
    return f
}

private inline fun safe(fail: () -> Unit, block: () -> Unit) {
    try { block() } catch (t: Throwable) { log("예외: $t"); fail() }
}

object Car {
    private lateinit var vehicle: Vehicle
    private val light get() = vehicle.getLight()
    private val hvac get() = vehicle.getHVAC()
    private val seat get() = vehicle.getSeat()
    private val door get() = vehicle.getDoor()

    private val DRIVER = SeatArea(SeatValue.ROW_1_LEFT)
    private val doors = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT).map { DoorArea(it) }

    // 장치마다 쓸 영역. 능력 확인(check…Capabilities)으로 운전석이 든 영역을 고르고, 못 받으면 운전석.
    private var ambArea = DRIVER
    private var fanArea = DRIVER
    private var tempArea = DRIVER
    private var recircArea = DRIVER
    private var powerArea = DRIVER
    private var seatHeatArea = DRIVER
    private var readingArea = DRIVER
    private var occArea = DRIVER
    var tempMin = 16f; private set
    var tempMax = 30f; private set

    private var started = false
    private var gearWatch: GearWatch? = null
    var gear: Int? = null; private set
    /** AAOS 차 속성(기어 연결 뒤에 생김). 시험용 읽기에 씁니다. */
    @Volatile var props: android.car.hardware.property.CarPropertyManager? = null
    var onGear: (() -> Unit)? = null

    /** 앱 전체의 Context(서비스와 화면이 함께 씁니다). */
    lateinit var ctx: Context; private set
    val ready get() = ::ctx.isInitialized

    /**
     * 차례 번호. 새 쪽잠을 시작하거나 되돌리기를 시작하면 올라갑니다.
     * apply()는 보낼 때의 번호를 기억해 두고, 번호가 바뀐 뒤에 도착한 앞 차례의 다시 읽기와 재시도는 버립니다.
     * (되돌린 값을 앞 차례의 늦은 재시도가 다시 쉼 값으로 바꾸지 않게 합니다.)
     */
    @Volatile var epoch = 0; private set
    fun bump() { epoch++; log("차례 번호 $epoch") }

    /** 내기 순환 상태. 알림(registerHvacRecirculationState)으로 받고, 못 받으면 null. 쉼 알림의 환기 신호에 씁니다. */
    @Volatile var recircOn: Boolean? = null; private set

    private fun pick(areas: Collection<SeatArea>): SeatArea =
        areas.firstOrNull { it.values.contains(SeatValue.ROW_1_LEFT) } ?: areas.firstOrNull() ?: DRIVER

    fun start(ctx: Context) {
        if (started) return
        started = true
        this.ctx = ctx.applicationContext
        vehicle = Vehicle(ctx.applicationContext)
        safe({}) { vehicle.initialize() }
        safe({}) {
            light.checkAmbientLightBrightnessCapabilities(
                onComplete = { _: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> ambArea = pick(m.keys); log("무드등 영역 $ambArea 범위 ${m[ambArea]?.minValue}~${m[ambArea]?.maxValue}") },
                onFailed = { e: Exception -> log("무드등 능력 실패 $e") })
            light.checkReadingLightSwitchCapabilities(
                onComplete = { _: CapabilityConfig, m: Map<SeatArea, List<LightSwitch>> -> readingArea = pick(m.keys) },
                onFailed = { e: Exception -> log("독서등 능력 실패 $e") })
            hvac.checkHvacFanSpeedCapabilities(
                onComplete = { _: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> fanArea = pick(m.keys); log("풍량 영역 $fanArea 범위 ${m[fanArea]?.minValue}~${m[fanArea]?.maxValue}") },
                onFailed = { e: Exception -> log("풍량 능력 실패 $e") })
            hvac.checkHvacTemperatureCapabilities(
                onComplete = { _: CapabilityConfig, m: Map<SeatArea, AreaBounds<Float>>, _: HvacTemperatureConfigArray? ->
                    tempArea = pick(m.keys)
                    m[tempArea]?.let { b -> (b.minValue as? Number)?.let { tempMin = it.toFloat() }; (b.maxValue as? Number)?.let { tempMax = it.toFloat() } }
                    log("온도 영역 $tempArea 범위 $tempMin~$tempMax")
                },
                onFailed = { e: Exception -> log("온도 능력 실패 $e") })
            hvac.checkHvacRecirculationCapabilities(
                onComplete = { _: CapabilityConfig, l: List<SeatArea> -> recircArea = pick(l); main.post { watchRecircState() } },
                onFailed = { e: Exception -> log("내기 순환 능력 실패 $e"); main.post { watchRecircState() } })
            hvac.checkHvacPowerCapabilities(
                onComplete = { _: CapabilityConfig, l: List<SeatArea> -> powerArea = pick(l) },
                onFailed = { e: Exception -> log("공조 전원 능력 실패 $e") })
            seat.checkSeatOccupancyCapabilities(
                onComplete = { _: CapabilityConfig, l: List<SeatArea> -> occArea = pick(l); log("탑승 영역 ${l.size}개 중 $occArea") },
                onFailed = { e: Exception -> log("탑승 능력 실패 $e") })
            seat.checkSeatTemperatureCapabilities(
                onComplete = { _: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> seatHeatArea = pick(m.keys) },
                onFailed = { e: Exception -> log("시트 열선 능력 실패 $e") })
        }
        checkLock { r -> log("문 잠금 능력 $r") }
        checkMore()
        gearWatch = GearWatch(ctx.applicationContext) { v ->
            main.post {
                gear = v
                log("기어 ${gearName(v)}")
                Nap.onGear()        // 화면이 없어도(서비스만 살아 있어도) 되돌리기와 P에서 마저 돌려놓기가 일어나게
                onGear?.invoke()    // 화면 갱신
            }
        }.also { it.start() }
    }

    val isPark get() = gear == android.car.VehicleGear.GEAR_PARK

    fun gearName(v: Int? = gear) = when (v) {
        null -> "모름"
        android.car.VehicleGear.GEAR_PARK -> "P"
        android.car.VehicleGear.GEAR_REVERSE -> "R"
        android.car.VehicleGear.GEAR_NEUTRAL -> "N"
        android.car.VehicleGear.GEAR_DRIVE -> "D"
        else -> "기타($v)"
    }

    // ───── 장치들 ─────

    val ambient = Dev<Int>("무드등 밝기",
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { light.getAmbientLightBrightness(ambArea, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { light.setAmbientLightBrightness(ambArea, v, onComplete = { _: SeatArea, _: Int -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val ambientColor = Dev<Int>("무드등 색",
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { light.getAmbientLightBrightnessColor(ambArea, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { light.setAmbientLightBrightnessColor(ambArea, v, onComplete = { _: SeatArea, _: Int -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val ambientSwitch = Dev<LightSwitch>("무드등 켜기",
        { cb -> val f = once<LightSwitch?>(null, cb = cb); safe({ f(null) }) { light.getAmbientLightSwitch(ambArea, onComplete = { v: LightSwitch? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { light.setAmbientLightSwitch(ambArea, v, onComplete = { _: SeatArea, _: LightSwitch? -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val cabin = Dev<LightSwitch>("실내등",
        { cb -> val f = once<LightSwitch?>(null, cb = cb); safe({ f(null) }) { light.getCabinLightSwitch(onComplete = { v: LightSwitch? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { light.setCabinLightSwitch(v, onComplete = { _: LightSwitch? -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val reading = Dev<LightSwitch>("독서등",
        { cb -> val f = once<LightSwitch?>(null, cb = cb); safe({ f(null) }) { light.getReadingLightSwitch(readingArea, onComplete = { v: LightSwitch? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { light.setReadingLightSwitch(readingArea, v, onComplete = { _: LightSwitch? -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val hvacPower = Dev<Boolean>("공조 전원",
        { cb -> val f = once<Boolean?>(null, cb = cb); safe({ f(null) }) { hvac.getHvacPowerState(powerArea, onComplete = { v: Boolean? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { hvac.setHvacPower(powerArea, v, onComplete = { _: SeatArea, _: Boolean? -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val temp = Dev<Float>("공조 온도",
        { cb -> val f = once<Float?>(null, cb = cb); safe({ f(null) }) { hvac.getHvacTemperature(tempArea, TemperatureUnit.CELSIUS, onComplete = { v: Float? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { hvac.setHvacTemperature(tempArea, v, TemperatureUnit.CELSIUS, onComplete = { _: SeatArea, _: Float? -> f(true) }, onFailed = { _: Exception -> f(false) }) } },
        { a, b -> abs(a - b) < 0.26f })

    val fan = Dev<Int>("공조 풍량",
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { hvac.getHvacFanSpeed(fanArea, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { hvac.setHvacFanSpeed(fanArea, v, onComplete = { _: SeatArea, _: Int? -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val recirc = Dev<Boolean>("공조 순환",
        { cb -> val f = once<Boolean?>(null, cb = cb); safe({ f(null) }) { hvac.getHvacRecirculationState(recircArea, onComplete = { v: Boolean? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { hvac.setHvacRecirculation(recircArea, v, onComplete = { _: SeatArea, _: Boolean? -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    val seatHeat = Dev<Int>("시트 열선",
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { seat.getHvacSeatTemperature(seatHeatArea, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { seat.setHvacSeatTemperature(seatHeatArea, v, onComplete = { _: SeatArea, _: Int? -> f(true) }, onFailed = { _: Exception -> f(false) }) } })

    // 문 잠금(쪽잠 차례에서 씀). 에뮬레이터에서는 네 문을 묶은 영역만 받아들였습니다(운전석 문 하나는 "지원하지 않는 영역").
    // 능력 확인으로 받은 영역 가운데 운전석 문이 든 것을 쓰고, 못 받으면 네 문 묶음.
    private var lockArea = DoorArea(*doors.flatMap { it.values.toList() }.toTypedArray())
    fun checkLock(cb: (String) -> Unit) = safe({ cb("예외") }) {
        door.checkDoorLockCapabilities(
            onComplete = { c: CapabilityConfig, l: List<DoorArea> -> (l.firstOrNull { it.values.contains(DoorValue.ROW_1_LEFT) } ?: l.firstOrNull())?.let { lockArea = it }; main.post { cb("접근 ${c.accessType.name}, 영역 ${l.size}개: " + l.joinToString { a -> a.values.joinToString("+") { it.name } }) } },
            onFailed = { e: Exception -> main.post { cb("실패 $e") } })
    }
    val doorLock = Dev<Boolean>("문 잠금",
        { cb -> val f = once<Boolean?>(null, cb = cb); safe({ f(null) }) { door.getDoorLockState(lockArea, onComplete = { v: Boolean? -> f(v) }, onFailed = { e: Exception -> log("문 잠금 읽기 실패 $e"); f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { door.setDoorLock(lockArea, v, onComplete = { _: DoorArea, _: Boolean? -> f(true) }, onFailed = { e: Exception -> log("문 잠금 실패 $e"); f(false) }) } })

    /** 시험용: 내기 순환이 바뀔 때 오는 알림을 기록합니다. */
    fun watchRecirc() = safe({}) {
        hvac.registerHvacRecirculationState(recircArea, object : ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { log("장치 시험: 내기 순환 알림 $state") }
            override fun onFailed(e: Exception) { log("장치 시험: 내기 순환 알림 실패 $e") }
        })
        log("장치 시험: 내기 순환 알림 등록함")
    }

    /** 시험용: AAOS 표준 값 읽기(IGNITION_STATE, PERF_VEHICLE_SPEED). */
    fun readAaos() {
        val pm = props ?: return log("장치 시험: AAOS 연결 전")
        try { log("장치 시험: IGNITION_STATE = ${pm.getIntProperty(android.car.VehiclePropertyIds.IGNITION_STATE, 0)}") } catch (t: Throwable) { log("장치 시험: IGNITION_STATE 실패 $t") }
        try { log("장치 시험: PERF_VEHICLE_SPEED = ${pm.getFloatProperty(android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)} m/s") } catch (t: Throwable) { log("장치 시험: PERF_VEHICLE_SPEED 실패 $t") }
    }

    /**
     * 값을 보내고 1.5초 뒤 다시 읽어 실제로 바뀌었는지 봅니다. 안 바뀌면 한 번 더 보냅니다.
     * 보낼 때의 차례 번호(epoch)가 그사이 바뀌면 다시 읽기와 재시도를 하지 않고 false로 끝냅니다.
     */
    fun <T : Any> apply(dev: Dev<T>, v: T, tries: Int = 2, cb: (Boolean) -> Unit = {}) = applyIn(epoch, dev, v, tries, cb)

    private fun <T : Any> applyIn(ep: Int, dev: Dev<T>, v: T, tries: Int, cb: (Boolean) -> Unit) {
        if (ep != epoch) { log("${dev.name}: 앞 차례(${ep})의 보내기 $v 를 버림"); return cb(false) }
        dev.set(v) { sdkOk ->
            main.postDelayed({
                if (ep != epoch) { log("${dev.name}: 앞 차례(${ep})의 다시 읽기를 버림(보낸 값 $v)"); cb(false); return@postDelayed }
                dev.get { now ->
                    // 내기 순환은 알림이 칼람 자신이 바꾼 값을 놓칠 때가 있어(에뮬레이터에서 봄), 다시 읽은 값으로도 맞춥니다.
                    if (dev === recirc) (now as? Boolean)?.let { if (recircOn != it) log("내기 순환 다시 읽음 $it"); recircOn = it }
                    val ok = now != null && dev.same(now, v)
                    log("${dev.name}: 보냄 $v (SDK 답 ${if (sdkOk) "성공" else "실패"}), 다시 읽음 $now → ${if (ok) "바뀜" else "안 바뀜"}")
                    if (ok || tries <= 1) cb(ok) else applyIn(ep, dev, v, tries - 1, cb)
                }
            }, 1500)
        }
    }

    /** 쉬기 전 값을 저장하고 되살릴 때 이름으로 장치를 찾습니다. 0.4에서 공조 더하기, 창, 시트 자세가 붙었습니다. */
    val all: List<Dev<*>> get() = listOf(ambient, ambientColor, ambientSwitch, cabin, reading, hvacPower, temp, fan, recirc, seatHeat, doorLock) + extra + windows + seats

    // ───── 0.4: 시트 편한 자세, 창, 공조 더하기 (Pleos Vehicle SDK 2.3.2 Seat, HVAC, Window, SteeringWheel, SideMirror) ─────

    private val window get() = vehicle.getWindow()
    private val wheel get() = vehicle.getSteeringWheel()
    private val mirror get() = vehicle.getSideMirror()

    private var ventArea = DRIVER
    private var cleanArea = DRIVER
    private var dirArea = DRIVER
    private var curTempArea = DRIVER
    var dirOptions: List<FanDirectionType> = emptyList(); private set
    private var mirrorArea = MirrorArea(MirrorValue.DRIVER_LEFT, MirrorValue.DRIVER_RIGHT)
    private var winLockArea = WindowArea(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    private val FRONT = WindowArea(WindowValue.FRONT_WINDSHIELD)
    var winMin = 0; private set
    var winMax = 10; private set
    /** 블라인드, 스마트 글라스가 달린 차인가(능력 확인 결과). null이면 아직 모름 */
    @Volatile var blindAreas: List<WindowArea>? = null; private set
    @Volatile var glassOk: Boolean? = null; private set

    /** 시트 축 하나. 위치를 읽고(getCurrentSeat…Position), 움직이고 멈춥니다(moveSeat…, MoveControl). 범위는 능력 확인에서 받습니다. */
    class Axis(
        val name: String,
        val key: String,
        val read: (SeatArea, (Int?) -> Unit, (Exception) -> Unit) -> Unit,
        val move: (SeatArea, MoveControl, (SeatArea, MoveControl?) -> Unit, (Exception) -> Unit) -> Unit,
    ) {
        @Volatile var min = 0; @Volatile var max = 100; @Volatile var known = false
        /** 같은 자리로 보는 폭: 범위의 20분의 1(0~100이면 5, -10~10이면 1) */
        val tol get() = maxOf(1, (max - min) / 20)
        /** 위치 값이 바뀌지 않은 채 움직이라고 보낸 시간(ms, FORWARD는 +, BACKWARD는 -). 되돌릴 때 반대로 같은 시간만큼 움직입니다 */
        @Volatile var blindMs = 0L
    }

    val backrest = Axis("등받이", "backrest", { a, ok, no -> seat.getCurrentSeatBackrestAnglePosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatBackrestAngle(a, m, ok, no) })
    val height = Axis("시트 높이", "height", { a, ok, no -> seat.getCurrentSeatHeightPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatHeight(a, m, ok, no) })
    val foreAft = Axis("시트 앞뒤", "foreaft", { a, ok, no -> seat.getCurrentSeatForeAftPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatForeAft(a, m, ok, no) })
    val tilt = Axis("쿠션 기울기", "tilt", { a, ok, no -> seat.getCurrentSeatTiltPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatTilt(a, m, ok, no) })
    val depth = Axis("쿠션 길이", "depth", { a, ok, no -> seat.getCurrentSeatDepthPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatDepth(a, m, ok, no) })
    val lumbar = Axis("허리 받침", "lumbar", { a, ok, no -> seat.getCurrentSeatLumbarForeAftPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatLumbarForeAft(a, m, ok, no) })
    val lumbarSide = Axis("허리 옆 받침", "lumbarside", { a, ok, no -> seat.getCurrentSeatLumbarSideSupportPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatLumbarSideSupport(a, m, ok, no) })
    val headAngle = Axis("머리 받침 각도", "headangle", { a, ok, no -> seat.getCurrentSeatHeadrestAnglePosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatHeadrestAngle(a, m, ok, no) })
    val headHeight = Axis("머리 받침 높이", "headheight", { a, ok, no -> seat.getCurrentSeatHeadrestHeightAftPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatHeadrestHeight(a, m, ok, no) })
    val headForeAft = Axis("머리 받침 앞뒤", "headforeaft", { a, ok, no -> seat.getCurrentSeatHeadrestForeAftPosition(a, ok, no) }, { a, m, ok, no -> seat.moveSeatHeadrestForeAft(a, m, ok, no) })
    val axes = listOf(backrest, height, foreAft, tilt, depth, lumbar, lumbarSide, headAngle, headHeight, headForeAft)

    /** 0~100 축에서 같은 자리로 보는 폭(에뮬레이터 콘솔의 시트 모터는 100ms에 5씩 움직임). 축마다는 Axis.tol */
    const val SEAT_TOL = 5

    fun readAxis(ax: Axis, cb: (Int?) -> Unit) {
        val f = once<Int?>(null, 1500, cb)
        safe({ f(null) }) { ax.read(DRIVER, { v -> f(v) }, { _ -> f(null) }) }
    }

    private fun sendMove(ax: Axis, m: MoveControl) = safe({}) { ax.move(DRIVER, m, { _, _ -> }, { e -> log("${ax.name} ${m.name} 실패 $e") }) }

    /**
     * 목표 위치까지 천천히 움직이고 멈춥니다. 100ms마다 위치를 읽어 목표에 닿으면 STOP.
     * 시간 제한(기본 15초)이 지나거나 1.5초 동안 위치가 그대로면(끝에 닿음, 지원 안 함) STOP.
     * 기어가 P가 아니면 움직이지 않고, 움직이는 중에 P를 벗어나면 바로 STOP. cb는 STOP 뒤 다시 읽은 위치(못 읽으면 null).
     */
    fun moveTo(ax: Axis, target0: Int, timeoutMs: Long = 15000, cb: (Int?) -> Unit) {
        val target = if (ax.known) target0.coerceIn(ax.min, ax.max) else target0
        if (!isPark) { log("${ax.name}: 기어 ${gearName()}라 움직이지 않음"); return cb(null) }
        readAxis(ax) { start ->
            if (start == null) { log("${ax.name}: 위치를 읽지 못해 움직이지 않음"); return@readAxis cb(null) }
            if (abs(start - target) <= ax.tol) { log("${ax.name}: 지금 $start, 목표 $target 안이라 움직이지 않음"); return@readAxis cb(start) }
            val dir = if (target > start) MoveControl.FORWARD else MoveControl.BACKWARD
            val t0 = android.os.SystemClock.uptimeMillis()
            var last = start
            var lastChange = t0
            var stopped = false
            fun stop(why: String) {
                if (stopped) return
                stopped = true
                sendMove(ax, MoveControl.STOP)
                val sent = android.os.SystemClock.uptimeMillis() - t0
                if (last == start) { ax.blindMs += if (dir == MoveControl.FORWARD) sent else -sent; log("${ax.name}: 위치 값이 바뀌지 않음(움직임을 확인하지 못함), 보낸 시간 ${sent}ms, 되돌릴 때 반대로 움직일 시간 ${ax.blindMs}ms") }
                main.postDelayed({ readAxis(ax) { end -> log("${ax.name}: ${dir.name} $start → 목표 $target, $why, STOP 보낸 뒤 다시 읽음 $end"); cb(end) } }, 300)
            }
            sendMove(ax, dir)
            fun poll() {
                if (stopped) return
                if (!isPark) return stop("기어가 P를 벗어나 멈춤")
                readAxis(ax) { now ->
                    if (stopped) return@readAxis
                    val t = android.os.SystemClock.uptimeMillis()
                    if (now != null && now != last) { last = now; lastChange = t }
                    when {
                        now != null && (if (dir == MoveControl.FORWARD) now >= target - ax.tol / 2 else now <= target + ax.tol / 2) -> stop("목표에 닿아 멈춤")
                        t - t0 > timeoutMs -> stop("시간 제한 ${timeoutMs / 1000}초로 멈춤")
                        t - lastChange > 800 -> stop("0.8초 동안 위치가 그대로라 멈춤")
                        else -> main.postDelayed({ poll() }, 100)
                    }
                }
            }
            main.postDelayed({ poll() }, 100)
        }
    }

    /** 시트 축을 장치(Dev)로: get은 위치, set은 목표까지 움직이기(성공 = 목표 근처에 섬). */
    private fun seatDev(ax: Axis) = Dev<Int>(ax.name,
        { cb -> readAxis(ax, cb) },
        { v, cb -> moveTo(ax, v) { end -> cb(end != null && abs(end - v) <= ax.tol) } },
        { a, b -> abs(a - b) <= ax.tol })

    /**
     * 위치를 모를 때(읽기 실패, 또는 움직이기 전과 후가 모두 0으로 같음): 정한 시간만큼 보내고 STOP합니다(P에서만).
     * 보낸 시간은 blindMs에 적어, 되돌릴 때 unblind가 반대로 같은 시간만큼 보냅니다. cb는 STOP 뒤 다시 읽은 위치.
     */
    fun pushFor(ax: Axis, dir: MoveControl, ms: Long, cb: (Int?) -> Unit) {
        if (!isPark) { log("${ax.name}: 기어 ${gearName()}라 움직이지 않음"); return cb(null) }
        readAxis(ax) { start ->
            if (!isPark) { log("${ax.name}: 기어 ${gearName()}라 움직이지 않음"); return@readAxis cb(null) }
            sendMove(ax, dir)
            val t0 = android.os.SystemClock.uptimeMillis()
            fun end(why: String) {
                sendMove(ax, MoveControl.STOP)
                val sent = android.os.SystemClock.uptimeMillis() - t0
                ax.blindMs += if (dir == MoveControl.FORWARD) sent else -sent
                main.postDelayed({ readAxis(ax) { e -> log("${ax.name}: 위치를 몰라 ${dir.name}로 ${sent}ms 보내고 STOP($why), 보내기 전 위치 $start, STOP 뒤 다시 읽음 $e, 되돌릴 때 반대로 보낼 시간 ${ax.blindMs}ms"); cb(e) } }, 300)
            }
            // 보내는 동안 P를 벗어나면 바로 멈춤
            fun watchGear() { if (android.os.SystemClock.uptimeMillis() - t0 >= ms) return end("정한 시간 ${ms}ms"); if (!isPark) return end("기어가 P를 벗어나 멈춤"); main.postDelayed({ watchGear() }, 50) }
            main.postDelayed({ watchGear() }, 50)
        }
    }

    /**
     * 위치 값이 바뀌지 않아 움직임을 확인하지 못한 축(에뮬레이터에서 봄)을, 보낸 시간만큼 반대로 움직여 되돌립니다(P에서만).
     * 확인할 수 없으므로 cb(false)로 끝내 화면에 '직접 확인해 주세요'로 보입니다. 움직이지 않았던 축이면 cb(true).
     */
    fun unblind(ax: Axis, cb: (Boolean) -> Unit) {
        val ms = ax.blindMs
        if (ms == 0L) return cb(true)
        if (!isPark) { log("${ax.name}: 기어 ${gearName()}라 반대로 움직이지 않음"); return cb(false) }
        ax.blindMs = 0
        val m = if (ms > 0) MoveControl.BACKWARD else MoveControl.FORWARD
        sendMove(ax, m)
        main.postDelayed({ sendMove(ax, MoveControl.STOP); log("${ax.name}: 위치를 못 읽어 보낸 시간만큼 반대로(${m.name} ${abs(ms)}ms) 움직이고 STOP"); cb(false) }, abs(ms))
    }
    val seats: List<Dev<Int>> = axes.map { seatDev(it) }
    fun seatOf(ax: Axis): Dev<Int> = seats[axes.indexOf(ax)]

    val seatVent = Dev<Int>("시트 통풍",
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { seat.getHvacSeatVentilationState(ventArea, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { seat.setHvacSeatVentilation(ventArea, v, onComplete = { _: SeatArea, _: Int? -> f(true) }, onFailed = { e: Exception -> log("시트 통풍 실패 $e"); f(false) }) } })

    val defrost = Dev<Boolean>("앞유리 김 서림 없애기",
        { cb -> val f = once<Boolean?>(null, cb = cb); safe({ f(null) }) { hvac.getHvacDefrosterState(FRONT, onComplete = { v: Boolean? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { hvac.setHvacDefroster(FRONT, v, onComplete = { _: WindowArea, _: Boolean? -> f(true) }, onFailed = { e: Exception -> log("김 서림 없애기 실패 $e"); f(false) }) } })

    val airCleaner = Dev<Boolean>("공기청정",
        { cb -> val f = once<Boolean?>(null, cb = cb); safe({ f(null) }) { hvac.getHvacAirCleanerState(cleanArea, onComplete = { v: Boolean? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { hvac.setHvacAirCleaner(cleanArea, v, onComplete = { _: SeatArea, _: Boolean? -> f(true) }, onFailed = { e: Exception -> log("공기청정 실패 $e"); f(false) }) } })

    val fanDir = Dev<FanDirectionType>("바람 방향",
        { cb -> val f = once<FanDirectionType?>(null, cb = cb); safe({ f(null) }) { hvac.getHvacFanDirection(dirArea, onComplete = { v: FanDirectionType? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { hvac.setHvacFanDirection(dirArea, v, onComplete = { _: SeatArea, _: FanDirectionType? -> f(true) }, onFailed = { e: Exception -> log("바람 방향 실패 $e"); f(false) }) } })

    val wheelHeat = Dev<Int>("핸들 열선",
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { wheel.getHvacSteeringWheelHeatState(onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { wheel.setHvacSteeringWheelHeat(v, onComplete = { _: Int? -> f(true) }, onFailed = { e: Exception -> log("핸들 열선 실패 $e"); f(false) }) } })

    val mirrorHeat = Dev<Int>("사이드미러 열선",
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { mirror.getHvacSideMirrorHeatState(mirrorArea, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { mirror.setHvacSideMirrorHeat(mirrorArea, v, onComplete = { _: MirrorArea, _: Int? -> f(true) }, onFailed = { e: Exception -> log("사이드미러 열선 실패 $e"); f(false) }) } })

    val windowLock = Dev<Boolean>("창 잠금",
        { cb -> val f = once<Boolean?>(null, cb = cb); safe({ f(null) }) { window.getCurrentWindowLockState(winLockArea, onComplete = { v: Boolean? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { window.setWindowLock(winLockArea, v, onComplete = { _: WindowArea, _: Boolean? -> f(true) }, onFailed = { e: Exception -> log("창 잠금 실패 $e"); f(false) }) } })

    val smartGlass = Dev<Boolean>("스마트 글라스",
        { cb -> val f = once<Boolean?>(null, cb = cb); safe({ f(null) }) { window.getCurrentSmartGlassState(onComplete = { v: Boolean? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { window.setSmartGlassState(v, onComplete = { _: Boolean? -> f(true) }, onFailed = { e: Exception -> log("스마트 글라스 실패 $e"); f(false) }) } })

    private fun windowDev(name: String, a: WindowArea) = Dev<Int>(name,
        { cb -> val f = once<Int?>(null, cb = cb); safe({ f(null) }) { window.getCurrentWindowPosition(a, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) } },
        { v, cb -> val f = once(false, cb = cb); safe({ f(false) }) { window.setWindowPosition(a, v, onComplete = { _: WindowArea, _: Int? -> f(true) }, onFailed = { e: Exception -> log("$name 실패 $e"); f(false) }) } })
    val winFL = windowDev("창 위치(앞 왼쪽)", WindowArea(WindowValue.ROW_1_LEFT))
    val winFR = windowDev("창 위치(앞 오른쪽)", WindowArea(WindowValue.ROW_1_RIGHT))
    val winRL = windowDev("창 위치(뒤 왼쪽)", WindowArea(WindowValue.ROW_2_LEFT))
    val winRR = windowDev("창 위치(뒤 오른쪽)", WindowArea(WindowValue.ROW_2_RIGHT))
    val windows: List<Dev<Int>> = listOf(winFL, winFR, winRL, winRR)

    /** 쉬기 전 값을 적어 두는 0.4의 공조와 창 장치(블라인드는 움직이는 장치라 따로) */
    val extra: List<Dev<*>> get() = listOf(seatVent, defrost, airCleaner, fanDir, wheelHeat, mirrorHeat, windowLock, smartGlass)

    /** 지금 실내 온도(℃). HVAC.getHvacTemperatureCurrent. 못 읽으면 null */
    fun cabinTemp(cb: (Float?) -> Unit) {
        val f = once<Float?>(null, cb = cb)
        safe({ f(null) }) { hvac.getHvacTemperatureCurrent(curTempArea, TemperatureUnit.CELSIUS, onComplete = { v: Float? -> f(v) }, onFailed = { e: Exception -> log("실내 온도 읽기 실패 $e"); f(null) }) }
    }

    /** 뒷자리(2열 왼쪽, 가운데, 오른쪽) 탑승 값. Seat.getCurrentSeatOccupancy. 셋 다 false면 false, 하나라도 true면 true, 못 읽은 것이 있으면 null */
    fun rearOccupied(cb: (Boolean?, String) -> Unit) {
        val rear = listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        val got = arrayOfNulls<Boolean>(rear.size)
        var left = rear.size
        rear.forEachIndexed { i, v ->
            val f = once<Boolean?>(null, 1500) { r -> got[i] = r; if (--left == 0) {
                val s = rear.indices.joinToString { k -> "${rear[k].name}=${got[k]}" }
                cb(if (got.any { it == true }) true else if (got.all { it == false }) false else null, s)
            } }
            safe({ f(null) }) { seat.getCurrentSeatOccupancy(SeatArea(v), onComplete = { b: Boolean? -> f(b) }, onFailed = { _: Exception -> f(null) }) }
        }
    }

    /** 창 넷의 위치. 하나라도 열렸으면(최소값보다 크면) true, 못 읽으면 null */
    fun windowsOpen(cb: (Boolean?, String) -> Unit) {
        val got = arrayOfNulls<Int>(windows.size)
        var left = windows.size
        windows.forEachIndexed { i, d ->
            d.get { v -> got[i] = v; if (--left == 0) {
                val s = windows.indices.joinToString { k -> "${windows[k].name}=${got[k]}" }
                cb(if (got.all { it == null }) null else got.any { (it ?: winMin) > winMin }, s)
            } }
        }
    }

    /** 블라인드: 지원하는 창이 있으면 끝까지 움직입니다(닫는 쪽은 BACKWARD로 가정). 지원하지 않으면 건너뜁니다. */
    fun blind(close: Boolean, cb: (String) -> Unit) {
        val areas = blindAreas
        if (areas.isNullOrEmpty()) { cb("블라인드 지원 안 함(능력 확인 ${if (areas == null) "답 없음" else "영역 0개"}), 건너뜀"); return }
        val m = if (close) MoveControl.BACKWARD else MoveControl.FORWARD
        areas.forEach { a -> safe({}) { window.moveWindowBlind(a, m, onComplete = { _: WindowArea, _: MoveControl? -> }, onFailed = { e: Exception -> log("블라인드 ${m.name} 실패 $e") }) } }
        main.postDelayed({
            areas.forEach { a -> safe({}) { window.moveWindowBlind(a, MoveControl.STOP, onComplete = { _: WindowArea, _: MoveControl? -> }, onFailed = { _: Exception -> }) } }
            areas.firstOrNull()?.let { a -> safe({}) { window.getCurrentWindowBlindPosition(a, onComplete = { v: Int? -> main.post { cb("블라인드 ${m.name} 8초 뒤 STOP, 다시 읽음 $v") } }, onFailed = { e: Exception -> main.post { cb("블라인드 위치 읽기 실패 $e") } }) } }
        }, 8000)
    }

    private fun checkMore() = safe({}) {
        val ranges = listOf<Pair<Axis, ((CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit, (Exception) -> Unit) -> Unit>>(
            backrest to { ok, no -> seat.checkSeatBackrestAnglePositionCapabilities(ok, no) },
            height to { ok, no -> seat.checkSeatHeightPositionCapabilities(ok, no) },
            foreAft to { ok, no -> seat.checkSeatForeAftPositionCapabilities(ok, no) },
            tilt to { ok, no -> seat.checkSeatTiltPositionCapabilities(ok, no) },
            depth to { ok, no -> seat.checkSeatDepthPositionCapabilities(ok, no) },
            lumbar to { ok, no -> seat.checkSeatLumbarForeAftPositionCapabilities(ok, no) },
            lumbarSide to { ok, no -> seat.checkSeatLumbarSideSupportPositionCapabilities(ok, no) },
            headAngle to { ok, no -> seat.checkSeatHeadrestAnglePositionCapabilities(ok, no) },
            headHeight to { ok, no -> seat.checkSeatHeadrestHeightPositionCapabilities(ok, no) },
            headForeAft to { ok, no -> seat.checkSeatHeadrestForeAftPositionCapabilities(ok, no) },
        )
        ranges.forEach { (ax, check) ->
            safe({}) {
                check({ _, m ->
                    val b = m.entries.firstOrNull { it.key.values.contains(SeatValue.ROW_1_LEFT) }?.value
                    if (b != null) { (b.minValue as? Number)?.let { ax.min = it.toInt() }; (b.maxValue as? Number)?.let { ax.max = it.toInt() }; ax.known = true }
                    log("시트 능력 ${ax.name}: 운전석 범위 ${if (b == null) "없음" else "${ax.min}~${ax.max}"}")
                }, { e -> log("시트 능력 ${ax.name} 실패 $e") })
            }
        }
        seat.checkSeatVentilationCapabilities(
            onComplete = { _: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> -> ventArea = pick(m.keys); log("시트 통풍 영역 ${ventArea.values.toList()} 범위 ${m[ventArea]?.minValue}~${m[ventArea]?.maxValue}") },
            onFailed = { e: Exception -> log("시트 통풍 능력 실패 $e") })
        hvac.checkHvacAirCleanerCapabilities(
            onComplete = { _: CapabilityConfig, l: List<SeatArea> -> cleanArea = pick(l); log("공기청정 영역 ${l.size}개 중 ${cleanArea.values.toList()}") },
            onFailed = { e: Exception -> log("공기청정 능력 실패 $e") })
        hvac.checkHvacFanDirectionCapabilities(
            onComplete = { _: CapabilityConfig, m: Map<SeatArea, List<FanDirectionType>> -> dirArea = pick(m.keys); dirOptions = m[dirArea] ?: emptyList(); log("바람 방향 영역 ${dirArea.values.toList()} 고를 수 있는 값 $dirOptions") },
            onFailed = { e: Exception -> log("바람 방향 능력 실패 $e") })
        hvac.checkHvacTemperatureCurrentCapabilities(
            onComplete = { _: CapabilityConfig, l: List<SeatArea> -> curTempArea = pick(l); log("실내 온도 영역 ${l.size}개 중 ${curTempArea.values.toList()}") },
            onFailed = { e: Exception -> log("실내 온도 능력 실패 $e") })
        mirror.checkHvacSideMirrorHeatCapabilities(
            onComplete = { _: CapabilityConfig, m: Map<MirrorArea, AreaBounds<Int>> -> m.keys.maxByOrNull { it.values.size }?.let { mirrorArea = it }; log("사이드미러 열선 영역 ${mirrorArea.values.toList()} (받은 영역 ${m.size}개)") },
            onFailed = { e: Exception -> log("사이드미러 열선 능력 실패 $e") })
        wheel.checkHvacSteeringWheelHeatCapabilities(
            onComplete = { _: CapabilityConfig, b: AreaBounds<Int> -> log("핸들 열선 범위 ${b.minValue}~${b.maxValue}") },
            onFailed = { e: Exception -> log("핸들 열선 능력 실패 $e") })
        window.checkWindowLockCapabilities(
            onComplete = { _: CapabilityConfig, l: List<WindowArea> -> (l.firstOrNull { it.values.contains(WindowValue.ROW_1_LEFT) } ?: l.maxByOrNull { it.values.size })?.let { winLockArea = it }; log("창 잠금 영역 ${winLockArea.values.toList()} (받은 영역 ${l.size}개)") },
            onFailed = { e: Exception -> log("창 잠금 능력 실패 $e") })
        window.checkWindowPositionCapabilities(
            onComplete = { _: CapabilityConfig, m: Map<WindowArea, AreaBounds<Int>> ->
                m.entries.firstOrNull { it.key.values.contains(WindowValue.ROW_1_LEFT) }?.value?.let { b -> (b.minValue as? Number)?.let { winMin = it.toInt() }; (b.maxValue as? Number)?.let { winMax = it.toInt() } }
                log("창 위치 영역 ${m.size}개, 앞 왼쪽 범위 $winMin~$winMax")
            },
            onFailed = { e: Exception -> log("창 위치 능력 실패 $e") })
        window.checkMoveWindowBlindCapabilities(
            onComplete = { _: CapabilityConfig, l: List<WindowArea> -> blindAreas = l; log("블라인드 영역 ${l.size}개") },
            onFailed = { e: Exception -> blindAreas = emptyList(); log("블라인드 능력 실패(지원 안 함으로 봄) $e") })
        window.checkSmartGlassStateCapabilities(
            onComplete = { c: CapabilityConfig -> glassOk = true; log("스마트 글라스 능력 ${c.accessType.name}") },
            onFailed = { e: Exception -> glassOk = false; log("스마트 글라스 능력 실패(지원 안 함으로 봄) $e") })
    }

    // ───── 알림으로 받는 값(폴링을 줄이려고 씁니다) ─────

    private var recircWatching = false
    private fun watchRecircState() {
        if (recircWatching) return
        recircWatching = true
        safe({ recircWatching = false }) {
            hvac.registerHvacRecirculationState(recircArea, object : ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener {
                override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { main.post { if (recircOn != state) log("내기 순환 알림 $state"); recircOn = state } }
                override fun onFailed(e: Exception) { log("내기 순환 알림 실패 $e") }
            })
            log("내기 순환 알림 등록함(영역 $recircArea)")
        }
        recirc.get { v -> if (v != null && recircOn == null) { recircOn = v; log("내기 순환 처음 값 $v") } }
    }

    /** 내기 순환을 한 번 다시 읽습니다(알림을 놓쳤을 때를 위한 받침. 쉼 알림이 달리는 중에 15초마다 부름). */
    fun refreshRecirc() = recirc.get { v -> if (v != null && v != recircOn) { log("내기 순환 다시 읽음 $v"); recircOn = v } }

    private val doorListeners = ArrayList<Pair<DoorArea, ai.pleos.playground.vehicle.listener.DoorPositionListener>>()

    /** 쪽잠 동안 네 문의 열림 알림을 받습니다. 하나라도 열리면 onOpen. */
    fun watchDoors(onOpen: () -> Unit) {
        unwatchDoors()
        doors.forEach { a ->
            val l = object : ai.pleos.playground.vehicle.listener.DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) { if ((pos ?: 0) > 0) main.post { log("문 열림 알림 ${a.values.toList()} = $pos"); onOpen() } }
                override fun onFailed(e: Exception) { log("문 열림 알림 실패 $e") }
            }
            safe({}) { door.registerDoorPosition(a, l); doorListeners.add(a to l) }
        }
        log("문 열림 알림 ${doorListeners.size}개 등록함")
    }

    fun unwatchDoors() {
        doorListeners.forEach { (a, l) -> safe({}) { door.unregisterDoorPosition(a, l) } }
        doorListeners.clear()
    }

    /** 확인 없이 보내기만 합니다(무드등을 서서히 바꾸는 중간 값). */
    fun <T : Any> send(dev: Dev<T>, v: T) = dev.set(v) {}

    /** 문 넷 가운데 하나라도 열렸는지, 운전석에 사람이 있는지. 못 읽으면 null. */
    fun readSafety(cb: (doorOpen: Boolean?, driverIn: Boolean?) -> Unit) {
        val pos = arrayOfNulls<Int>(doors.size)
        var left = doors.size + 1
        var seated: Boolean? = null
        fun done() {
            if (--left > 0) return
            val anyOpen = if (pos.all { it == null }) null else pos.any { (it ?: 0) > 0 }
            cb(anyOpen, seated)
        }
        doors.forEachIndexed { i, a ->
            val f = once<Int?>(null, 1500) { v -> pos[i] = v; done() }
            safe({ f(null) }) { door.getCurrentDoorPosition(a, onComplete = { v: Int? -> f(v) }, onFailed = { _: Exception -> f(null) }) }
        }
        val g = once<Boolean?>(null, 1500) { v -> seated = v; done() }
        safe({ g(null) }) { seat.getCurrentSeatOccupancy(occArea, onComplete = { v: Boolean? -> g(v) }, onFailed = { _: Exception -> g(null) }) }
    }

    /** 이번 주행 동안 달린 시간(분). Pleos Vehicle SDK 2.3.2 Odometer.getTripCurrentDrivingTime */
    var tripError = ""; private set
    fun tripMinutes(cb: (Int?) -> Unit) {
        tripError = "답이 없음"
        val f = once<Int?>(null, cb = cb)
        safe({ f(null) }) { vehicle.getOdometer().getTripCurrentDrivingTime(onComplete = { v: Int? -> f(v) }, onFailed = { e: Exception -> tripError = e.message ?: e.toString(); log("달린 시간 실패 $e"); f(null) }) }
    }
}

/**
 * AAOS(android.car)로 기어를 봅니다. Pleos Vehicle SDK의 Gear는 @hide라 쓰지 않습니다.
 * 콜백은 화면(Activity)이 가려져도 앱 프로세스에서 받습니다.
 */
private class GearWatch(private val ctx: Context, private val onGear: (Int?) -> Unit) {
    fun start() = Thread({
        try {
            Class.forName("android.car.Car")
            val car = android.car.Car.createCar(ctx) ?: return@Thread log("Car 연결 실패")
            val pm = car.getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
            Car.props = pm
            val id = android.car.VehiclePropertyIds.GEAR_SELECTION
            onGear(pm.getIntProperty(id, 0))
            pm.registerCallback(object : android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: android.car.hardware.CarPropertyValue<*>) { onGear(value.value as? Int) }
                override fun onErrorEvent(propertyId: Int, areaId: Int) { log("기어 오류 $propertyId") }
            }, id, android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE)
        } catch (t: Throwable) {
            log("기어를 읽지 못함: $t")
        }
    }, "gear").start()
}
