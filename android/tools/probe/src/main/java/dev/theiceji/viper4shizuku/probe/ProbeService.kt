package dev.theiceji.viper4shizuku.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.theiceji.viper4shizuku.capture.CaptureConfig
import dev.theiceji.viper4shizuku.capture.CaptureStatus
import dev.theiceji.viper4shizuku.capture.ShizukuCapture
import dev.theiceji.viper4shizuku.engine.LoopbackRenderer
import dev.theiceji.viper4shizuku.engine.NativeDsp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (mediaPlayback) that owns the capture path during device tests: it starts the
 * Shizuku capture, feeds the pipe through [NativeDsp] passthrough and plays it with
 * [LoopbackRenderer]. Driven entirely by intents so the test scripts need no UI interaction,
 * and started from a visible Activity so Android 17's while-in-use rule is satisfied.
 */
class ProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var capture: ShizukuCapture
    private var dsp: NativeDsp? = null
    private var renderer: LoopbackRenderer? = null

    @Volatile
    private var bypass = false

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        capture = ShizukuCapture(this)
        capture.onCreate()
        scope.launch {
            capture.state.collect { onCaptureState(it) }
        }
        scope.launch {
            capture.mixState.collect { ProbeLog.result("capture.mixState", it) }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                bypass = intent.getBooleanExtra(EXTRA_BYPASS, false)
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48_000)
                val useFloat = intent.getBooleanExtra(EXTRA_FLOAT, true)
                val encoding = if (useFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
                ProbeLog.result("capture.requested", "sr=$sampleRate float=$useFloat bypass=$bypass")
                capture.start(CaptureConfig(sampleRate = sampleRate, encoding = encoding))
            }

            ACTION_STATUS -> logStatus()

            ACTION_STOP -> {
                stopEngine("intent stop")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun onCaptureState(state: ShizukuCapture.State) {
        ProbeLog.result("capture.state", state::class.simpleName)
        when (state) {
            is ShizukuCapture.State.Capturing -> {
                ProbeLog.result("capture.serviceUid", state.serviceUid)
                startRenderer(state)
            }

            is ShizukuCapture.State.Error -> ProbeLog.error("capture: ${state.message}")
            is ShizukuCapture.State.PermissionRequired ->
                ProbeLog.info("Shizuku permission required; grant it from the probe UI then retry")

            is ShizukuCapture.State.ShizukuUnavailable ->
                ProbeLog.info("Shizuku not running; start it (wireless debugging) then retry")

            is ShizukuCapture.State.Ready -> ProbeLog.info("capture ready")
        }
    }

    private fun startRenderer(state: ShizukuCapture.State.Capturing) {
        renderer?.stop()
        val engine = NativeDsp(state.config.sampleRate).also { dsp = it }
        val r =
            LoopbackRenderer(
                source = state.pipe,
                sampleRate = state.config.sampleRate,
                encoding = state.config.encoding,
                dsp = engine,
                onStopped = { reason ->
                    ProbeLog.result("renderer.stopped", reason)
                },
            )
        r.bypass = bypass
        renderer = r
        r.start()
        ProbeLog.result("renderer.started", "sr=${state.config.sampleRate}")
        scope.launch {
            // Periodic stats so S-13 (latency) and dropout checks have data.
            repeat(Int.MAX_VALUE) {
                kotlinx.coroutines.delay(1000)
                val s = r.stats
                if (!s.running) return@launch
                ProbeLog.result(
                    "renderer.stats",
                    "in=${s.framesIn} dropped=${s.framesDropped} underruns=${s.underruns} " +
                        "peak=%.3f trackLatencyMs=%d backlogMs=%d".format(s.peak, s.trackLatencyMs, s.backlogMs),
                )
            }
        }
    }

    private fun logStatus() {
        val s: CaptureStatus? = capture.status()
        if (s == null) {
            ProbeLog.result("status", "no-service")
        } else {
            ProbeLog.result(
                "status",
                "capturing=${s.capturing} uid=${s.uid} sr=${s.sampleRate} mixState=${s.mixState} " +
                    "frames=${s.framesCaptured} reg=${s.registrationId}",
            )
        }
        renderer?.let { ProbeLog.result("renderer.running", it.stats.running) }
    }

    private fun stopEngine(reason: String) {
        renderer?.stop()
        renderer = null
        dsp?.close()
        dsp = null
        capture.stop()
        ProbeLog.result("engine.stopped", reason)
    }

    override fun onDestroy() {
        stopEngine("service destroyed")
        capture.onDestroy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ViPER4Shizuku Probe",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ViPER4Shizuku Probe")
            .setContentText("Capture test running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "viper4shizuku_probe"

        const val ACTION_START = "dev.theiceji.viper4shizuku.probe.START"
        const val ACTION_STOP = "dev.theiceji.viper4shizuku.probe.STOP"
        const val ACTION_STATUS = "dev.theiceji.viper4shizuku.probe.STATUS"
        const val EXTRA_BYPASS = "bypass"
        const val EXTRA_SAMPLE_RATE = "sampleRate"
        const val EXTRA_FLOAT = "float"

        fun start(
            context: Context,
            bypass: Boolean,
            sampleRate: Int,
            float: Boolean,
        ) {
            val intent =
                Intent(context, ProbeService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_BYPASS, bypass)
                    putExtra(EXTRA_SAMPLE_RATE, sampleRate)
                    putExtra(EXTRA_FLOAT, float)
                }
            context.startForegroundService(intent)
        }
    }
}
