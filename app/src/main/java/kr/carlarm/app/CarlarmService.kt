package kr.carlarm.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 쪽잠이 도는 동안과 쉼 알림이 켜진 동안만 앞에서 도는 서비스입니다(Pleos 지침: 불필요한 상주 최소화).
 * 앱 화면이 가려지거나(주행 제한) 다른 앱을 써도 프로세스가 남아 깨우기와 되돌리기, 달린 시간 세기가 이어지게 합니다.
 * 할 일이 끝나면 스스로 꺼집니다. 프로세스가 죽으면 시스템이 다시 켜고(START_STICKY), 적어 둔 값으로 남은 차례를 잇습니다.
 */
class CarlarmService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Records.init(this)
        Car.start(this)
        Voice.start(this)
        goForeground()
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carlarm:keep").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L) // 길어도 3시간이면 놓음
            }
        log("서비스 켜짐")
        if ((Nap.state == State.IDLE && Nap.hasSaved()) || (!Remind.on && Remind.hasSaved())) {
            recovering = true
            // 차 SDK가 붙을 시간을 두고 되살립니다.
            main.postDelayed({
                Nap.recover()
                Remind.recover()
                recovering = false
                sync()
            }, 2500)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_ALARM) log("알람 받침이 서비스를 깨움(쪽잠 상태 ${Nap.state})")
        if (intent?.action == ACTION_WOKE) {
            log("알림의 '일어났어요'를 누름")
            Nap.restore("일어났어요를 누름(알림)")
        }
        if (!recovering && !needed()) { log("서비스: 할 일이 없어 끔"); stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        log("서비스 꺼짐")
        super.onDestroy()
    }

    private fun goForeground() {
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    companion object {
        private const val ID = 7
        private const val WAKE_ID = 8
        private const val CHANNEL = "carlarm"
        private const val WAKE_CHANNEL = "carlarm_wake"
        const val ACTION_WOKE = "kr.carlarm.app.WOKE"
        const val ACTION_ALARM = "kr.carlarm.app.ALARM"

        private fun alarmPi(ctx: Context) = android.app.PendingIntent.getForegroundService(ctx, 3,
            Intent(ctx, CarlarmService::class.java).setAction(ACTION_ALARM),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

        /**
         * 받침 알람. 앱이 짧은 사이에 여러 번 죽으면 시스템이 서비스 다시 켜기를 30분까지 미룹니다(에뮬레이터에서 봄).
         * 그래서 다음 깨울 때를 시스템 알람(setAlarmClock)에도 걸어, 그때 앱이 죽어 있으면 시스템이 서비스를 깨우고
         * 서비스가 적어 둔 값으로 남은 차례를 잇게 합니다. 정확한 알람을 못 걸면 정확하지 않은 알람으로 대신합니다.
         */
        fun setBackstop(atWallMs: Long) {
            if (!Car.ready) return
            val ctx = Car.ctx
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmPi(ctx)
            val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(atWallMs))
            try {
                val show = android.app.PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atWallMs, show), pi)
                log("받침 알람 걸음(정확, $at)")
            } catch (t: Throwable) {
                try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMs, pi); log("받침 알람 걸음(정확하지 않음, $at): $t") }
                catch (t2: Throwable) { log("받침 알람 실패 $t2") }
            }
        }

        fun clearBackstop() {
            if (!Car.ready) return
            try { (Car.ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmPi(Car.ctx)); log("받침 알람 거둠") }
            catch (t: Throwable) { log("받침 알람 거두기 실패 $t") }
        }
        @Volatile var running = false; private set
        @Volatile private var recovering = false
        /** 칼람 화면이 앞에 보이는가(MainActivity의 onResume, onPause가 적음) */
        @Volatile var uiVisible = false

        /**
         * 깨우는 중에 칼람 화면이 앞에 없으면(다른 앱을 쓰는 중), '일어났어요' 버튼이 달린 알림을 띄웁니다.
         * 버튼을 누르면 서비스가 받아 바로 되돌립니다. 알림을 누르면 칼람 화면이 열립니다.
         * on=false면 알림을 거둡니다(되돌리기를 시작할 때).
         */
        fun wakeAlert(on: Boolean) {
            if (!Car.ready) return
            val ctx = Car.ctx
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                if (!on) { nm.cancel(WAKE_ID); return }
                if (uiVisible) return
                if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(WAKE_CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(WAKE_CHANNEL, "칼람 깨우기", NotificationManager.IMPORTANCE_HIGH))
                }
                val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                val open = android.app.PendingIntent.getActivity(ctx, 1,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
                val woke = android.app.PendingIntent.getService(ctx, 2,
                    Intent(ctx, CarlarmService::class.java).setAction(ACTION_WOKE), flags)
                val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, WAKE_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
                val n = b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("일어날 시간이에요")
                    .setContentText("${Nap.minutes}분 쉬었어요. 완전히 깬 뒤에 출발하세요.")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .addAction(Notification.Action.Builder(null, "일어났어요", woke).build())
                    .build()
                nm.notify(WAKE_ID, n)
                log("깨우기 알림 띄움(칼람 화면이 앞에 없음)")
            } catch (t: Throwable) {
                log("깨우기 알림 실패 $t")
            }
        }

        fun needed() = Nap.active || Nap.state == State.RESTORING || Remind.on

        private fun text() = when {
            Nap.active -> "쪽잠 중이에요. 깨울 때까지 칼람이 차를 지켜봐요."
            Nap.state == State.RESTORING -> "쉬기 전으로 되돌리는 중이에요."
            Remind.on -> "쉼 알림이 달린 시간을 세고 있어요."
            else -> "칼람"
        }

        private fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "칼람", NotificationManager.IMPORTANCE_LOW))
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            return b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("칼람")
                .setContentText(text())
                .setOngoing(true)
                .build()
        }

        /** 쪽잠과 쉼 알림의 상태가 바뀔 때 부릅니다. 필요하면 켜고, 할 일이 없으면 끕니다. */
        fun sync() {
            if (!Car.ready) return
            val ctx = Car.ctx
            try {
                if (needed()) {
                    if (!running) ctx.startForegroundService(Intent(ctx, CarlarmService::class.java))
                    else (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(ctx))
                } else if (running && !recovering) {
                    ctx.stopService(Intent(ctx, CarlarmService::class.java))
                }
            } catch (t: Throwable) {
                log("서비스 켜기/끄기 실패 $t")
            }
        }

        /** 화면을 열 때 부릅니다. 적어 둔 쪽잠이나 쉼 알림이 있는데 서비스가 없으면 켜서 되살립니다. */
        fun recoverIfNeeded(ctx: Context) {
            if (running) return
            if (Nap.state == State.IDLE && Nap.hasSaved() || !Remind.on && Remind.hasSaved()) {
                try { ctx.startForegroundService(Intent(ctx.applicationContext, CarlarmService::class.java)) } catch (t: Throwable) { log("서비스 켜기 실패 $t") }
            }
        }
    }
}
