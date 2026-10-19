package dev.theiceji.viper4shizuku.capture

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * System audio capture, app-side controller. Owns the Shizuku lifecycle and the bound
 * [ICaptureService], and hands the app a captured-PCM pipe to feed the renderer.
 *
 * Shizuku must be started again after every reboot; while it is not running, nothing here works
 * and there is no fallback (the in-app player aside). The service process runs as uid 2000.
 */
class ShizukuCapture(
    private val context: Context,
) {
    sealed interface State {
        /** Shizuku app/binder not present; user must install and start it. */
        data object ShizukuUnavailable : State

        /** Binder alive but Shizuku permission not granted. */
        data object PermissionRequired : State

        /** Ready to capture. */
        data object Ready : State

        /** User service bound and capturing; [pipe] carries PCM to the renderer. */
        data class Capturing(
            val pipe: ParcelFileDescriptor,
            val config: CaptureConfig,
            val serviceUid: Int,
        ) : State

        data class Error(
            val message: String,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.ShizukuUnavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _mixState = MutableStateFlow(CaptureStatus.MIX_STATE_UNKNOWN)
    val mixState: StateFlow<Int> = _mixState.asStateFlow()

    /** Why capture last stopped on its own (service teardown, watchdog); cleared by [start]. */
    private val _lastStopReason = MutableStateFlow<String?>(null)
    val lastStopReason: StateFlow<String?> = _lastStopReason.asStateFlow()

    private val main = Handler(Looper.getMainLooper())

    /** Local token linked-to-death by the service: lets it drop the policy if the app dies. */
    private val appToken = Binder()

    private var service: ICaptureService? = null
    private var pendingStart: (() -> Unit)? = null

    private val args =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, CaptureUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("capture")
            .debuggable(false)
            .version(SERVICE_VERSION)
            .tag("viper4shizuku-capture")

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                if (binder.pingBinder()) {
                    val svc = ICaptureService.Stub.asInterface(binder)
                    service = svc
                    Log.i(TAG, "user service connected, uid=${runCatching { svc.uid }.getOrDefault(-1)}")
                    pendingStart?.also {
                        pendingStart = null
                        it()
                    }
                } else {
                    _state.value = State.Error("user service binder dead on connect")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TAG, "user service disconnected")
                service = null
                if (_state.value is State.Capturing) {
                    _state.value = State.Error("capture service disconnected")
                }
            }
        }

    private val captureCallback =
        object : ICaptureCallback.Stub() {
            override fun onStopped(reason: String) {
                main.post {
                    Log.i(TAG, "capture stopped: $reason")
                    if (_state.value is State.Capturing) {
                        _lastStopReason.value = reason
                        _state.value = State.Ready
                    }
                }
            }

            override fun onMixStateChanged(state: Int) {
                _mixState.value = state
            }
        }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refreshState()
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            service = null
            _state.value = State.ShizukuUnavailable
        }

    fun onCreate() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshState()
    }

    fun onDestroy() {
        stop()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    fun refreshState() {
        val next =
            when {
                !Shizuku.pingBinder() -> State.ShizukuUnavailable
                !isPermissionGranted() -> State.PermissionRequired
                _state.value is State.Capturing -> return
                else -> State.Ready
            }
        _state.value = next
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            _state.value = State.ShizukuUnavailable
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            _state.value = State.PermissionRequired
            return
        }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun isPermissionGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** Binds the user service if needed, then registers the policy and returns PCM via [state]. */
    fun start(
        config: CaptureConfig = CaptureConfig(),
        excludedUids: IntArray = IntArray(0),
    ) {
        if (!Shizuku.pingBinder()) {
            _state.value = State.ShizukuUnavailable
            return
        }
        if (!isPermissionGranted()) {
            _state.value = State.PermissionRequired
            return
        }
        _lastStopReason.value = null
        val selfUid = context.applicationInfo.uid
        val exclude = (excludedUids.toList() + selfUid).distinct().toIntArray()

        val doStart = {
            val svc = service
            if (svc == null) {
                _state.value = State.Error("user service not connected")
            } else {
                try {
                    val pipe: ParcelFileDescriptor? = svc.start(config.toBundle(), exclude, appToken, captureCallback)
                    checkNotNull(pipe) { "capture service returned no pipe" }
                    _state.value = State.Capturing(pipe, config, runCatching { svc.uid }.getOrDefault(-1))
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    // The service tears down on its own failures; stop again in case the policy
                    // outlived a failure it could not see (a live policy silences all media).
                    runCatching { svc.stop() }
                    _state.value = State.Error(e.message ?: "capture start failed")
                }
            }
        }

        if (service != null) {
            doStart()
        } else {
            pendingStart = doStart
            Shizuku.bindUserService(args, connection)
        }
    }

    fun updateExcludedUids(excludedUids: IntArray) {
        val selfUid = context.applicationInfo.uid
        val exclude = (excludedUids.toList() + selfUid).distinct().toIntArray()
        runCatching { service?.updateExcludedUids(exclude) }
    }

    fun status(): CaptureStatus? = runCatching { service?.getStatus()?.let { CaptureStatus.fromBundle(it) } }.getOrNull()

    fun stop() {
        runCatching { service?.stop() }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        if (_state.value is State.Capturing) _state.value = State.Ready
    }

    private companion object {
        const val TAG = "ViPER4ShizukuCapture"
        const val REQUEST_CODE = 4210
        /** Bump whenever CaptureUserService changes: Shizuku reuses a running service of the same version. */
        const val SERVICE_VERSION = 2
    }
}
