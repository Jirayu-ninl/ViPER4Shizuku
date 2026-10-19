package dev.theiceji.viper4shizuku.capture

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiopolicy.AudioMix
import android.media.audiopolicy.AudioMixingRule
import android.media.audiopolicy.AudioPolicy
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * System audio capture, privileged side. Instantiated by Shizuku inside a process running as
 * uid 2000 (com.android.shell), where non-SDK API restrictions do not apply and
 * MODIFY_AUDIO_ROUTING is held. Registers a LOOP_BACK-only [AudioPolicy] so matched app playback
 * is rerouted into a remote-submix (silent on the real output) that we capture and pipe to the
 * ViPER4Shizuku app.
 *
 * Fail-safe: the policy is torn down when the app binder dies, when the pipe reader goes away,
 * when the app stays alive but stops reading the pipe (watchdog), when start() fails after the
 * policy was registered, or when Shizuku destroys this service. If none fires while the policy
 * is live, all media on the device is silent, so every exit path must reach [teardown].
 */
class CaptureUserService(
    private val context: Context,
) : ICaptureService.Stub() {
    private val lock = Any()

    private var policy: AudioPolicy? = null
    private var mix: AudioMix? = null
    private var record: AudioRecord? = null
    private var pumpThread: Thread? = null
    private var writeEnd: ParcelFileDescriptor? = null
    private var callback: ICaptureCallback? = null
    private var appBinder: IBinder? = null
    private var appDeathRecipient: IBinder.DeathRecipient? = null

    @Volatile
    private var config: CaptureConfig = CaptureConfig()

    @Volatile
    private var excludedUids: IntArray = IntArray(0)

    @Volatile
    private var running = false
    private val framesCaptured = AtomicLong(0)

    override fun getUid(): Int = Process.myUid()

    override fun isCapturing(): Boolean = running

    override fun getStatus(): Bundle {
        synchronized(lock) {
            val m = mix
            return CaptureStatus(
                capturing = running,
                uid = Process.myUid(),
                sampleRate = config.sampleRate,
                encoding = config.encoding,
                mixState = runCatching { m?.mixState ?: CaptureStatus.MIX_STATE_UNKNOWN }
                    .getOrDefault(CaptureStatus.MIX_STATE_UNKNOWN),
                framesCaptured = framesCaptured.get(),
                registrationId = runCatching { policy?.registration }.getOrNull(),
            ).toBundle()
        }
    }

    override fun start(
        configBundle: Bundle,
        excluded: IntArray,
        binder: IBinder,
        cb: ICaptureCallback,
    ): ParcelFileDescriptor = asSelf {
        synchronized(lock) {
            if (running || policy != null) teardown("restart")
            val cfg = CaptureConfig.fromBundle(configBundle)
            config = cfg
            excludedUids = excluded
            callback = cb

            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val builtMix = buildMix(cfg, excludedUids)
            // setAudioPolicyStatusListener returns void in the framework, so it can't be chained
            // before build().
            val policyBuilder = AudioPolicy.Builder(context).addMix(builtMix)
            policyBuilder.setAudioPolicyStatusListener(statusListener)
            val builtPolicy = policyBuilder.build()

            val status = registerAudioPolicy(am, builtPolicy)
            if (status != 0) {
                throw startFailure("registerAudioPolicy failed: status=$status (uid=${Process.myUid()})")
            }

            // The policy is live from here on: matched playback is rerouted into the mix and is
            // silent on the speaker. Any failure before the pump runs must unregister it again.
            policy = builtPolicy
            mix = builtMix
            var pipe: Array<ParcelFileDescriptor>? = null
            val readEnd =
                try {
                    val ar = createSink(builtPolicy, builtMix, cfg)
                    record = ar
                    // Below API 31 the sink comes from the AudioRecord constructor, which does not
                    // throw when native init fails; it leaves the record uninitialized.
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        throw IOException("AudioRecord sink not initialized (state=${ar.state})")
                    }

                    val p = ParcelFileDescriptor.createPipe().also { pipe = it }
                    writeEnd = p[1]

                    appBinder = binder
                    appDeathRecipient =
                        IBinder.DeathRecipient {
                            Log.w(TAG, "app binder died; tearing down")
                            synchronized(lock) { teardown("app died") }
                        }.also { binder.linkToDeath(it, 0) }

                    framesCaptured.set(0)
                    running = true
                    ar.startRecording()
                    if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw IOException("AudioRecord did not start (recordingState=${ar.recordingState})")
                    }
                    pumpThread =
                        Thread({ pump(ar, p[1], cfg) }, "ViPER4ShizukuCapture").apply { start() }
                    p[0]
                } catch (e: Exception) {
                    pipe?.let { runCatching { it[0].close() } }
                    teardown("start failed: ${e.message}")
                    throw startFailure("capture start failed: ${e.message}", e)
                }
            Log.i(TAG, "capture started: reg=${runCatching { builtPolicy.registration }.getOrNull()}")
            readEnd
        }
    }

    override fun stop() {
        asSelf { synchronized(lock) { teardown("stop") } }
    }

    override fun updateExcludedUids(excluded: IntArray) {
        asSelf {
            synchronized(lock) {
                excludedUids = excluded
                val p = policy ?: return
                val oldMix = mix ?: return
                val newRule = buildRule(config, excluded)
                val result =
                    runCatching {
                        p.updateMixingRules(listOf(android.util.Pair(oldMix, newRule)))
                    }.getOrElse { -1 }
                Log.i(TAG, "updateExcludedUids -> $result")
            }
        }
    }

    override fun destroy() {
        asSelf { synchronized(lock) { teardown("shizuku destroy") } }
    }

    // --- internals ----------------------------------------------------------------------------

    /**
     * Runs [block] as this process (uid 2000) instead of as the binder caller. Framework code that
     * checks permissions in-process uses checkCallingOrSelfPermission, which during an incoming
     * call checks the ViPER4Shizuku app's uid: e.g. AudioPolicy.createAudioRecordSink then fails
     * its MODIFY_AUDIO_ROUTING check and returns null even though the policy registered fine.
     */
    private inline fun <T> asSelf(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun pump(
        ar: AudioRecord,
        out: ParcelFileDescriptor,
        cfg: CaptureConfig,
    ) {
        val bytesPerFrame = if (cfg.encoding == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
        val buffer = ByteBuffer.allocateDirect(cfg.readFrames * bytesPerFrame)
        val writer = PipeWriter(out.fileDescriptor, cfg.readerStallTimeoutMs) { running }
        var reason = "pump ended"
        try {
            while (running) {
                buffer.clear()
                val read = ar.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    reason = "AudioRecord.read=$read"
                    break
                }
                if (read == 0) continue
                buffer.position(0)
                buffer.limit(read)
                if (!writer.writeFully(buffer)) {
                    reason =
                        if (running) "watchdog: app stopped reading for ${cfg.readerStallTimeoutMs} ms" else "stopped"
                    break
                }
                framesCaptured.addAndGet((read / bytesPerFrame).toLong())
            }
        } catch (e: IOException) {
            reason = if (running) "pipe write failed: ${e.message}" else "stopped"
        } catch (e: Exception) {
            reason = "pump error: ${e.message}"
            Log.e(TAG, "pump failed", e)
        } finally {
            runCatching { out.close() }
            synchronized(lock) { if (running) teardown(reason) }
        }
    }

    // Only RuntimeExceptions (and RemoteException) are parceled back to the binder caller; a
    // checked exception thrown from this Kotlin stub would be dropped and the app would get null.
    private fun startFailure(
        message: String,
        cause: Throwable? = null,
    ): IllegalStateException = IllegalStateException(message, cause).also { Log.e(TAG, message, cause) }

    /** Must be called while holding [lock]. Idempotent. */
    private fun teardown(reason: String) {
        if (policy == null && !running) return
        Log.i(TAG, "teardown: $reason")
        running = false

        appBinder?.let { b -> appDeathRecipient?.let { runCatching { b.unlinkToDeath(it, 0) } } }
        appBinder = null
        appDeathRecipient = null

        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null

        policy?.let { p ->
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { unregisterAudioPolicy(am, p) }
        }
        policy = null
        mix = null

        writeEnd?.let { runCatching { it.close() } }
        writeEnd = null

        callback?.let { cb -> runCatching { cb.onStopped(reason) } }
        callback = null

        pumpThread?.let { if (it !== Thread.currentThread()) it.interrupt() }
        pumpThread = null
    }

    private val statusListener =
        object : AudioPolicy.AudioPolicyStatusListener() {
            override fun onMixStateUpdate(updated: AudioMix) {
                val state = runCatching { updated.mixState }.getOrDefault(CaptureStatus.MIX_STATE_UNKNOWN)
                runCatching { callback?.onMixStateChanged(state) }
            }
        }

    /**
     * Builds the AudioRecord that reads [mix]. AudioPolicy.createAudioRecordSink does the same,
     * but through the @SystemApi AudioRecord constructor, which takes its attribution from
     * ActivityThread's current Application: in this Shizuku process that is uid 2000 with package
     * dev.theiceji.viper4shizuku, and Android 16's audioserver rejects the pair ("invalid attr",
     * AudioRecord init status -1). Here the record is attributed to the package that owns uid 2000.
     */
    @SuppressLint("MissingPermission") // runs as the shell uid, which holds RECORD_AUDIO
    private fun createSink(
        policy: AudioPolicy,
        mix: AudioMix,
        cfg: CaptureConfig,
    ): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return policy.createAudioRecordSink(mix) ?: throw IOException("createAudioRecordSink returned null")
        }
        // Same attributes as createAudioRecordSink; these Builder methods are @SystemApi or @hide.
        val attrs = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java
            .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attrs, MediaRecorder.AudioSource.REMOTE_SUBMIX)
        val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        addTag.invoke(attrs, "addr=${mix.registration}")
        addTag.invoke(attrs, SUBMIX_FIXED_VOLUME)

        val format =
            AudioFormat.Builder()
                .setSampleRate(cfg.sampleRate)
                .setEncoding(cfg.encoding)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
        // Attribute the record to the package that owns this process's uid (com.android.shell).
        val uid = Process.myUid()
        val shellSource =
            AttributionSource.Builder(uid)
                .setPackageName(context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: SHELL_PACKAGE)
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setPid(Process.myPid()) }
                .build()
        val shellContext =
            object : ContextWrapper(context) {
                override fun getAttributionSource(): AttributionSource = shellSource
            }

        val builder =
            AudioRecord.Builder()
                .setContext(shellContext)
                .setAudioFormat(format)
                .setBufferSizeInBytes(
                    AudioRecord.getMinBufferSize(cfg.sampleRate, AudioFormat.CHANNEL_IN_STEREO, cfg.encoding),
                )
        AudioRecord.Builder::class.java
            .getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(builder, attrs.build())
        // Throws UnsupportedOperationException if native init fails.
        return builder.build()
    }

    private fun buildMix(
        cfg: CaptureConfig,
        excluded: IntArray,
    ): AudioMix {
        val format =
            AudioFormat.Builder()
                .setSampleRate(cfg.sampleRate)
                .setEncoding(cfg.encoding)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        return AudioMix.Builder(buildRule(cfg, excluded))
            .setFormat(format)
            .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)
            .build()
    }

    private fun buildRule(
        cfg: CaptureConfig,
        excluded: IntArray,
    ): AudioMixingRule {
        val builder = AudioMixingRule.Builder()
        for (usage in cfg.usages) {
            builder.addMixRule(
                AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE,
                AudioAttributes.Builder().setUsage(usage).build(),
            )
        }
        for (uid in excluded) {
            builder.excludeMixRule(AudioMixingRule.RULE_MATCH_UID, Integer.valueOf(uid))
        }
        return builder.build()
    }

    // AudioManager.registerAudioPolicy / unregisterAudioPolicy are @SystemApi and absent from the
    // public SDK, so they are reached by reflection. Everything else uses the hidden-api stubs.
    private fun registerAudioPolicy(
        am: AudioManager,
        p: AudioPolicy,
    ): Int =
        AudioManager::class.java
            .getMethod("registerAudioPolicy", AudioPolicy::class.java)
            .invoke(am, p) as Int

    private fun unregisterAudioPolicy(
        am: AudioManager,
        p: AudioPolicy,
    ) {
        AudioManager::class.java
            .getMethod("unregisterAudioPolicy", AudioPolicy::class.java)
            .invoke(am, p)
    }

    private companion object {
        const val TAG = "ViPER4ShizukuCapture"
        const val SHELL_PACKAGE = "com.android.shell"

        /** AudioRecord.SUBMIX_FIXED_VOLUME (@hide): records the submix at full volume. */
        const val SUBMIX_FIXED_VOLUME = "fixedVolume"
    }
}
