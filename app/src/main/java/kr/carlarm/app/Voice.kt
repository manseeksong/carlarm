package kr.carlarm.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 차의 말. Pleos TextToSpeech SDK 3.3.1, 차 안 엔진(Mode.ON_DEVICE).
 * 서버 방식은 앱 등록이 필요해 쓰지 않습니다.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    /** 마지막 말이 어디까지 갔는지(화면과 기록용) */
    var status = "아직 말하지 않았어요"; private set

    private val events = object : EventListener {
        override fun onReady() {}
        override fun onStart() { status = "말 시작(onStart)"; log("말: onStart") }
        override fun onDone() { status = "말 끝(onDone)"; log("말: onDone") }
        override fun onError(errorType: ErrorType) { status = "말 오류(${errorType.name})"; log("말: onError ${errorType.name}") }
        override fun onStop() { log("말: onStop") }
        override fun onUpdatedRms(rms: Double) {}
    }

    fun start(ctx: Context) {
        if (tts != null) return
        try {
            val t = TextToSpeech(ctx.applicationContext, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        ready = true
                        try { t.setLocale(Locale.KOREA) } catch (e: Throwable) { log("setLocale 예외 $e") }
                        log("말: 준비됨")
                        pending.forEach { speakNow(it) }
                        pending.clear()
                    }
                }
                override fun onFailure(errorType: ErrorType) { status = "말 준비 실패(${errorType.name})"; log("말: 준비 실패 ${errorType.name}") }
            })
            t.addEventListener(events)
        } catch (e: Throwable) {
            status = "말 준비 예외"
            log("말: 준비 예외 $e")
        }
    }

    fun say(text: String) {
        log("말 요청: $text")
        if (ready) speakNow(text) else pending.add(text)
    }

    private fun speakNow(text: String) {
        try { tts?.speak(text, true) } catch (e: Throwable) { status = "말 예외"; log("말: speak 예외 $e") }
    }
}

/**
 * 칼람의 소리. 소리 파일을 쓰지 않고 AudioTrack으로 그때그때 만듭니다.
 * 입면 소리(SLEEP): 빗소리처럼 들리게 거른 잡음이 천천히 숨 쉬듯 커졌다 작아짐.
 * 기상 소리(WAKE): 도, 미, 솔이 차례로 울리는 부드러운 종소리.
 */
object Sound {
    const val SLEEP = 1
    const val WAKE = 2
    @Volatile private var kind = 0
    @Volatile var gain = 0f
    @Volatile private var gen = 0
    private var thread: Thread? = null

    fun play(k: Int, g: Float) {
        kind = k
        gain = g
        if (thread == null) begin()
    }

    fun stop() {
        gen++
        thread = null
        gain = 0f
    }

    private fun begin() {
        val my = ++gen
        thread = Thread({
            val sr = 22050
            var track: AudioTrack? = null
            try {
                val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, sr))
                    .build()
                track = t
                t.play()
                log("소리: 시작")
                val buf = ShortArray(1024)
                var n = 0L
                var g = 0f
                var brown = 0f
                var low = 0f
                val notes = doubleArrayOf(523.25, 659.25, 783.99)
                while (gen == my) {
                    for (i in buf.indices) {
                        val s = n.toDouble() / sr
                        g += (gain - g) * 0.0002f // 소리 크기를 갑자기 바꾸지 않음
                        val v = if (kind == SLEEP) {
                            brown = (brown + (Random.nextFloat() * 2f - 1f) * 0.06f) * 0.985f
                            low += (brown - low) * 0.3f
                            low * (0.75f + 0.25f * sin(2 * PI * s / 7).toFloat())
                        } else {
                            val step = (s / 1.2).toInt()
                            val ts = s - step * 1.2
                            val f = notes[step % notes.size]
                            (sin(2 * PI * f * ts) * exp(-2.5 * ts) * 0.6).toFloat()
                        }
                        buf[i] = (v * g * 20000f).coerceIn(-32000f, 32000f).toInt().toShort()
                        n++
                    }
                    t.write(buf, 0, buf.size)
                }
            } catch (e: Throwable) {
                log("소리: 예외 $e")
            } finally {
                try { track?.stop(); track?.release() } catch (_: Throwable) {}
                log("소리: 끝")
            }
        }, "sound").also { it.start() }
    }
}
