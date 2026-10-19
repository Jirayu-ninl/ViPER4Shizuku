package dev.theiceji.viper4shizuku.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.theiceji.viper4shizuku.R
import dev.theiceji.viper4shizuku.SERVICE_CHANNEL_ID
import dev.theiceji.viper4shizuku.audio.AudioDevice
import dev.theiceji.viper4shizuku.audio.AudioOutputDetector
import dev.theiceji.viper4shizuku.capture.CaptureConfig
import dev.theiceji.viper4shizuku.capture.ShizukuCapture
import dev.theiceji.viper4shizuku.data.model.DeviceSettings
import dev.theiceji.viper4shizuku.data.repository.ViperRepository
import dev.theiceji.viper4shizuku.effect.EffectState
import dev.theiceji.viper4shizuku.effect.deserializeEffectPrefs
import dev.theiceji.viper4shizuku.effect.loadEffectStateFromPrefs
import dev.theiceji.viper4shizuku.effect.serializeEffectPrefs
import dev.theiceji.viper4shizuku.engine.InAppPlayer
import dev.theiceji.viper4shizuku.engine.LoopbackRenderer
import dev.theiceji.viper4shizuku.engine.NativeDsp
import dev.theiceji.viper4shizuku.utils.FileLogger
import dev.theiceji.viper4shizuku.viper.BulkLoader
import dev.theiceji.viper4shizuku.viper.DriverStatus
import dev.theiceji.viper4shizuku.viper.LocalTransport
import dev.theiceji.viper4shizuku.viper.ParamValue
import dev.theiceji.viper4shizuku.viper.ViperTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject

// The in-app player runs media3 APIs marked @UnstableApi.
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class ViperService : LifecycleService() {
    @Inject
    lateinit var repository: ViperRepository

    companion object {
        private const val NOTIFICATION_ID = 1

        // ViPERDDC coefficients exist only for 44.1/48 kHz; the engine runs at the mixer rate.
        private const val DEFAULT_SAMPLE_RATE = 48_000

        // How often the capture figures the UI shows are refreshed while capturing.
        private const val CAPTURE_STATS_INTERVAL_MS = 500L

        // Binding the Shizuku user service spawns a process; after this the UI stops waiting.
        private const val CAPTURE_START_TIMEOUT_MS = 10_000L

        const val ACTION_START = "dev.theiceji.viper4shizuku.service.START"
        const val ACTION_STOP = "dev.theiceji.viper4shizuku.service.STOP"
        const val ACTION_TOGGLE_MASTER = "dev.theiceji.viper4shizuku.service.TOGGLE_MASTER"
        const val EXTRA_MASTER_ENABLED = "dev.theiceji.viper4shizuku.service.EXTRA_MASTER_ENABLED"

        // Notification action: stop every engine (capture first), keep the service.
        const val ACTION_STOP_ENGINES = "dev.theiceji.viper4shizuku.service.STOP_ENGINES"

        // Start/stop capture without the UI (debug hooks for the device scripts).
        const val ACTION_START_CAPTURE = "dev.theiceji.viper4shizuku.service.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "dev.theiceji.viper4shizuku.service.STOP_CAPTURE"

        // In-app player: play a local audio file through the DSP (the no-root test harness path).
        const val ACTION_PLAY_URI = "dev.theiceji.viper4shizuku.service.PLAY_URI"
        const val ACTION_STOP_PLAYER = "dev.theiceji.viper4shizuku.service.STOP_PLAYER"
        const val ACTION_LOG_STATUS = "dev.theiceji.viper4shizuku.service.LOG_STATUS"
        const val EXTRA_URI = "dev.theiceji.viper4shizuku.service.EXTRA_URI"

        fun startService(context: Context) {
            val intent =
                Intent(context, ViperService::class.java).apply {
                    action = ACTION_START
                }
            context.startForegroundService(intent)
        }

        fun toggleMaster(
            context: Context,
            enabled: Boolean,
        ) {
            val intent =
                Intent(context, ViperService::class.java).apply {
                    action = ACTION_TOGGLE_MASTER
                    putExtra(EXTRA_MASTER_ENABLED, enabled)
                }
            context.startForegroundService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        val service: ViperService get() = this@ViperService
    }

    private val binder = LocalBinder()
    private val dsps = CopyOnWriteArrayList<NativeDsp>()
    private val transport = LocalTransport { dsps }
    private var audioOutputDetector: AudioOutputDetector? = null
    private var stateProvider: (() -> EffectState)? = null
    private var lastUiState: EffectState? = null

    // In-app media3 player. Owns its own NativeDsp, attached to the transport so UI
    // params reach it, while it is alive. Null when nothing is playing.
    private var inAppPlayer: InAppPlayer? = null
    private var playerStateJob: Job? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // System audio: other apps' media, rerouted by the Shizuku user service (uid 2000) and played
    // here through the DSP. The renderer and its DSP exist only while capturing.
    private lateinit var capture: ShizukuCapture
    private var captureRenderer: LoopbackRenderer? = null
    private var captureDsp: NativeDsp? = null
    private var captureStatsJob: Job? = null
    private var captureStarting = false

    // Why capture last stopped when it was not the user (or the capture service) stopping it.
    private var captureError: String? = null

    // False after ACTION_STOP, so a late update does not post the removed notification again.
    private var inForeground = true

    private val _captureState = MutableStateFlow(CaptureState())
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    init {
        installBulkSideChannels(transport)
    }

    private var bootMasterEnabled: Boolean = false
    private val masterEnabled: Boolean
        get() = stateProvider?.invoke()?.masterEnable ?: lastUiState?.masterEnable ?: bootMasterEnabled

    fun setStateProvider(provider: () -> EffectState) {
        stateProvider = provider
    }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        FileLogger.i("Service", "Service created")
        capture = ShizukuCapture(this)
        capture.onCreate()
        lifecycleScope.launch { capture.state.collect { onCaptureState(it) } }
        lifecycleScope.launch {
            // The settings dialog edits the list while capture runs; the mixing rule follows it.
            repository
                .getStringSetPreference(ViperRepository.PREF_EXCLUDED_APPS)
                .distinctUntilChanged()
                .drop(1)
                .collect { packages ->
                    if (captureRenderer != null) capture.updateExcludedUids(uidsOf(packages))
                }
        }
        lifecycleScope.launch {
            combine(captureState, playerState) { c, p -> Triple(c.status, p.active, p.title) }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshNotification() }
        }
        lifecycleScope.launch {
            ensureConfigLoaded()
            if (masterEnabled) {
                val state = loadEffectStateFromPrefs(repository)
                applyState(state, true)
            }
            startAudioOutputMonitor()
        }
    }

    private var configLoaded = false

    private suspend fun ensureConfigLoaded() {
        if (configLoaded) return
        bootMasterEnabled = repository.getBooleanPreference(ViperRepository.PREF_MASTER_ENABLE).first()
        configLoaded = true
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun installBulkSideChannels(t: ViperTransport) {
        t.bulkSideChannels = { ddc, kernel, force ->
            if (ddc.isNotEmpty()) applyDdcDevice(ddc, force)
            if (kernel.isNotEmpty()) applyConvolverKernel(kernel, force)
        }
    }

    // Kernel and DDC loads decode files and stream them in many chunks, so they run off the main
    // thread. One thread runs them in the order they were asked for, so a load queued for an older
    // state never lands after one the user asked for since.
    private val bulkExecutor = Executors.newSingleThreadExecutor { Thread(it, "ViPER4ShizukuBulk") }

    private fun runBulk(block: () -> Unit) {
        try {
            bulkExecutor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    FileLogger.e("Service", "Bulk load failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // The service is being destroyed; its engines are stopping too.
        }
    }

    private fun applyState(
        state: EffectState,
        masterOn: Boolean,
    ) {
        if (!masterOn || dsps.isEmpty()) return
        transport.pushFullState(state)
    }

    fun attachDsp(dsp: NativeDsp) {
        dsps.addIfAbsent(dsp)
        FileLogger.i("Service", "DSP attached (${dsps.size} active)")
        transport.invalidateBulk()
        lifecycleScope.launch {
            ensureConfigLoaded()
            if (!masterEnabled) return@launch
            applyState(stateProvider?.invoke() ?: loadEffectStateFromPrefs(repository), true)
        }
    }

    fun detachDsp(dsp: NativeDsp) {
        dsps.remove(dsp)
        FileLogger.i("Service", "DSP detached (${dsps.size} active)")
    }

    /**
     * In-app player: play a local audio file through the DSP. Builds the player (and its
     * DSP) on the main thread, attaches the DSP so the UI effect state flows into it, then
     * starts playback.
     */
    fun playLocalFile(
        uri: Uri,
        displayName: String? = null,
    ) {
        if (inAppPlayer == null) {
            val player = InAppPlayer(this, NativeDsp(DEFAULT_SAMPLE_RATE))
            inAppPlayer = player
            attachDsp(player.dsp)
            playerStateJob =
                lifecycleScope.launch {
                    player.state.collect { s ->
                        _playerState.value =
                            PlayerState(
                                active = s.uri != null,
                                title = s.title ?: s.uri?.lastPathSegment,
                                isPlaying = s.isPlaying,
                                error = s.error,
                            )
                    }
                }
            FileLogger.i("Service", "Player started")
        }
        inAppPlayer?.let { player ->
            player.bypass = !masterEnabled
            FileLogger.i("Service", "Player play, masterEnabled=$masterEnabled bypass=${player.bypass}")
            player.play(uri, displayName)
        }
    }

    fun togglePausePlayer() {
        inAppPlayer?.togglePause()
    }

    /** Engines must keep audio flowing with no processing while the master switch is off. */
    private fun engineBypass(bypass: Boolean) {
        inAppPlayer?.bypass = bypass
        captureRenderer?.bypass = bypass
    }

    fun stopPlayer() {
        val player = inAppPlayer ?: return
        inAppPlayer = null
        playerStateJob?.cancel()
        playerStateJob = null
        _playerState.value = PlayerState()
        detachDsp(player.dsp)
        player.close()
        player.dsp.close()
        FileLogger.i("Service", "Player stopped")
    }

    /**
     * System audio: asks the Shizuku user service to reroute other apps' media to
     * ViPER4Shizuku. The renderer starts once the capture pipe arrives (see [onCaptureState]).
     * Apps excluded in the settings stay out of the mix and play directly.
     */
    fun startCapture() {
        lifecycleScope.launch {
            val excluded = uidsOf(repository.getStringSetPreference(ViperRepository.PREF_EXCLUDED_APPS).first())
            captureStarting = true
            captureError = null
            publishCaptureState()
            FileLogger.i("Service", "Capture start, excluded uids=${excluded.toList()}")
            capture.start(CaptureConfig(sampleRate = DEFAULT_SAMPLE_RATE), excluded)
            // A user service that is not bound yet starts later, and the state stays Ready until
            // then. Any other state is already the outcome, and may equal the previous one, in
            // which case the state flow does not report it again.
            if (capture.state.value !is ShizukuCapture.State.Ready) captureStarting = false
            publishCaptureState()
            if (!captureStarting) return@launch
            delay(CAPTURE_START_TIMEOUT_MS)
            if (captureStarting && capture.state.value is ShizukuCapture.State.Ready) {
                captureStarting = false
                captureError = "the capture service did not start"
                publishCaptureState()
            }
        }
    }

    fun stopCapture() {
        // Drop the policy first: while it is registered, all matched media is silent on the
        // speaker, so it must not outlive anything that could fail below.
        capture.stop()
        captureStarting = false
        stopRenderer()
        publishCaptureState()
    }

    fun requestShizukuPermission() = capture.requestPermission()

    /** Re-reads whether Shizuku runs and is granted, e.g. after the user started it. */
    fun refreshCaptureState() {
        capture.refreshState()
        publishCaptureState()
    }

    private fun onCaptureState(state: ShizukuCapture.State) {
        FileLogger.i("Service", "Capture state: ${state::class.simpleName}")
        // The policy's mix is an output device as well. Name it before the device callbacks
        // queued behind this state run, so they do not switch the profile to it.
        if (state is ShizukuCapture.State.Capturing) {
            val registration = capture.status()?.registrationId
            if (registration != null) {
                AudioOutputDetector.addOwnPolicyRegistration(registration)
            } else {
                FileLogger.w("Service", "Capture policy registration unknown; its mix may switch the device profile")
            }
        }
        when (state) {
            is ShizukuCapture.State.Capturing -> startRenderer(state)
            // Ready also follows a stop the service made on its own (teardown, watchdog).
            else -> stopRenderer()
        }
        if (state !is ShizukuCapture.State.Ready) captureStarting = false
        publishCaptureState()
    }

    private fun startRenderer(state: ShizukuCapture.State.Capturing) {
        stopRenderer()
        captureStarting = false
        val dsp = NativeDsp(state.config.sampleRate)
        lateinit var renderer: LoopbackRenderer
        renderer =
            LoopbackRenderer(
                source = state.pipe,
                sampleRate = state.config.sampleRate,
                encoding = state.config.encoding,
                dsp = dsp,
                onStopped = { reason -> lifecycleScope.launch { onRendererStopped(renderer, reason) } },
            )
        renderer.bypass = !masterEnabled
        captureRenderer = renderer
        captureDsp = dsp
        attachDsp(dsp)
        renderer.start()
        FileLogger.i("Service", "Capture renderer started, uid=${state.serviceUid} sr=${state.config.sampleRate}")
        captureStatsJob =
            lifecycleScope.launch {
                while (isActive) {
                    publishCaptureState()
                    delay(CAPTURE_STATS_INTERVAL_MS)
                }
            }
    }

    /** Called on the main thread after a renderer ended, whoever ended it. */
    private fun onRendererStopped(
        renderer: LoopbackRenderer,
        reason: String,
    ) {
        if (captureRenderer !== renderer) return
        // The renderer died while capture still runs (pipe or output failure). Nothing plays the
        // rerouted audio any more, so drop the policy rather than leave media silent.
        FileLogger.w("Service", "Capture renderer stopped on its own: $reason")
        stopCapture()
        captureError = reason
        publishCaptureState()
    }

    private fun stopRenderer() {
        val renderer = captureRenderer ?: return
        captureRenderer = null
        captureStatsJob?.cancel()
        captureStatsJob = null
        renderer.stop()
        captureDsp?.let { dsp ->
            detachDsp(dsp)
            dsp.close()
        }
        captureDsp = null
        FileLogger.i("Service", "Capture renderer stopped")
    }

    private fun publishCaptureState() {
        val shizuku = capture.state.value
        val stats = captureRenderer?.stats
        _captureState.value =
            CaptureState(
                status =
                    when (shizuku) {
                        is ShizukuCapture.State.ShizukuUnavailable -> CaptureState.Status.SHIZUKU_UNAVAILABLE
                        is ShizukuCapture.State.PermissionRequired -> CaptureState.Status.PERMISSION_REQUIRED
                        is ShizukuCapture.State.Capturing -> CaptureState.Status.CAPTURING
                        is ShizukuCapture.State.Ready, is ShizukuCapture.State.Error ->
                            if (captureStarting) CaptureState.Status.STARTING else CaptureState.Status.READY
                    },
                // When the capture service stops on its own, the renderer then sees the pipe end;
                // the service's reason is the cause, so it goes before the renderer's.
                error =
                    (shizuku as? ShizukuCapture.State.Error)?.message
                        ?: capture.lastStopReason.value
                        ?: captureError,
                trackLatencyMs = stats?.trackLatencyMs ?: 0,
                backlogMs = stats?.backlogMs ?: 0,
                underruns = stats?.underruns ?: 0,
                framesDropped = stats?.framesDropped ?: 0,
            )
    }

    private fun uidsOf(packages: Set<String>): IntArray =
        packages
            .mapNotNull { pkg ->
                try {
                    packageManager.getPackageUid(pkg, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }.toIntArray()

    private var currentServiceDeviceId: String = AudioDevice.ID_SPEAKER

    private fun startAudioOutputMonitor() {
        val detector = AudioOutputDetector(this)
        audioOutputDetector = detector
        currentServiceDeviceId = detector.activeDevice.value.id
        lifecycleScope.launch {
            detector.activeDevice.collect { device ->
                if (device.id != currentServiceDeviceId) {
                    FileLogger.i(
                        "Service",
                        "Device changed: $currentServiceDeviceId -> ${device.id} (${device.name})",
                    )
                    currentServiceDeviceId = device.id
                    reapplyForDevice(device)
                }
            }
        }
    }

    private suspend fun reapplyForDevice(device: AudioDevice) {
        val saved = repository.getDeviceSettings(device.id)
        val state: EffectState =
            if (saved != null) {
                FileLogger.i("Service", "Loading device settings from DB for ${device.id}")
                val baseState = loadEffectStateFromPrefs(repository)
                val json = JSONObject(saved.settingsJson)
                deserializeEffectPrefs(json, baseState).also {
                    repository.updateDeviceLastConnected(device.id)
                }
            } else {
                FileLogger.i("Service", "No DB entry for ${device.id}, using DataStore defaults")
                val s = loadEffectStateFromPrefs(repository)
                val json = serializeEffectPrefs(s)
                repository.saveDeviceSettings(
                    DeviceSettings(
                        deviceId = device.id,
                        deviceName = device.name,
                        isHeadphone = device.isHeadphone,
                        settingsJson = json.toString(),
                    ),
                )
                s
            }
        applyState(state, masterEnabled)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                FileLogger.i("Service", "Service started")
            }

            ACTION_STOP -> {
                inForeground = false
                stopCapture()
                stopPlayer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_STOP_ENGINES -> {
                FileLogger.i("Service", "Engines stopped from the notification")
                stopCapture()
                stopPlayer()
            }

            ACTION_PLAY_URI -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (uri != null) playLocalFile(uri)
            }

            ACTION_STOP_PLAYER -> stopPlayer()

            ACTION_START_CAPTURE -> startCapture()

            ACTION_STOP_CAPTURE -> stopCapture()

            ACTION_LOG_STATUS -> {
                val status = transport.probeStatus()
                FileLogger.i(
                    "Service",
                    "status dsps=${dsps.size} renderer=${captureRenderer != null} player=${inAppPlayer != null} " +
                        "masterEnabled=$masterEnabled processedFrames=${status?.processedFrames} " +
                        "sr=${status?.sampleRate} kernelId=${status?.kernelId} ver=${status?.versionName} " +
                        "arch=${status?.arch} capture=${_captureState.value}",
                )
            }

            ACTION_TOGGLE_MASTER -> {
                val next = intent.getBooleanExtra(EXTRA_MASTER_ENABLED, false)
                engineBypass(!next)
                lifecycleScope.launch {
                    ensureConfigLoaded()
                    bootMasterEnabled = next
                    // The UI persists masterEnable before toggling, but guard against a stale
                    // snapshot so masterEnabled reflects the switch immediately.
                    val state = loadEffectStateFromPrefs(repository).copy(masterEnable = next)
                    stateProvider = null
                    lastUiState = state
                    applyState(state, next)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Queued loads are dropped. One still streaming goes on into DSPs that are not closed yet;
        // NativeDsp.close() waits for a call in flight, and later calls do nothing.
        bulkExecutor.shutdownNow()
        stopCapture()
        capture.onDestroy()
        stopPlayer()
        audioOutputDetector?.stop()
        audioOutputDetector = null
        FileLogger.i("Service", "Service destroyed")
        super.onDestroy()
    }

    fun setParam(
        param: Int,
        value: ParamValue,
    ) = transport.set(param, value)

    /**
     * The UI's whole state, master switch included. Engines follow its switch the same way they
     * follow ACTION_TOGGLE_MASTER from the tile, which may have dropped the state provider.
     */
    fun dispatchFullState(state: EffectState) {
        lastUiState = state
        engineBypass(!state.masterEnable)
        applyState(state, state.masterEnable)
    }

    // Kernel and DDC files live under the app's external files dir, where the UI imports them.
    private val bulkLoader by lazy {
        val root = getExternalFilesDir(null)
        BulkLoader(transport, File(root, "Kernel"), File(root, "DDC"))
    }

    /** Queues kernel [fileName] for every attached DSP; returns before it is streamed. */
    fun applyConvolverKernel(
        fileName: String,
        force: Boolean = false,
    ) = runBulk { bulkLoader.applyConvolverKernel(fileName, force) }

    /** Queues DDC device [name] for every attached DSP; returns before it is sent. */
    fun applyDdcDevice(
        name: String,
        force: Boolean = false,
    ) = runBulk { bulkLoader.applyDdcDevice(name, force) }

    fun probeDriverStatus(): DriverStatus? = transport.probeStatus()

    private fun refreshNotification() {
        if (!inForeground) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent =
            launchIntent?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val capturing = _captureState.value.status == CaptureState.Status.CAPTURING
        val player = _playerState.value
        val text =
            when {
                capturing -> getString(R.string.notification_text_capture)
                player.active -> getString(R.string.notification_text_player, player.title.orEmpty())
                else -> getString(R.string.notification_text)
            }
        val builder =
            NotificationCompat
                .Builder(this, SERVICE_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
        if (capturing || player.active) {
            val stop =
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, ViperService::class.java).setAction(ACTION_STOP_ENGINES),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(0, getString(R.string.notification_action_stop), stop)
        }
        return builder.build()
    }
}
