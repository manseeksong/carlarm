package kr.carlarm.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
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

    /** 쉬기 전 값을 저장하고 되살릴 때 이름으로 장치를 찾습니다. */
    val all: List<Dev<*>> get() = listOf(ambient, ambientColor, ambientSwitch, cabin, reading, hvacPower, temp, fan, recirc, seatHeat, doorLock)

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
