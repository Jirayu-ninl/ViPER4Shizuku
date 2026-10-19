package dev.theiceji.viper4shizuku.probe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import dev.theiceji.viper4shizuku.capture.ShizukuCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device-test driver UI and command surface. Humans use the buttons to grant Shizuku and start/stop
 * tests; the scripts use intent extras, e.g.
 *
 *   adb shell am start -n dev.theiceji.viper4shizuku.probe/.ProbeActivity -e cmd dsp
 *   adb shell am start -n dev.theiceji.viper4shizuku.probe/.ProbeActivity -e cmd capture --ez float true --ei sr 48000
 *   adb shell am start -n dev.theiceji.viper4shizuku.probe/.ProbeActivity -e cmd stop
 *
 * The capture FGS is started from here (a visible component) so Android 17's while-in-use rule
 * is satisfied.
 */
class ProbeActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var capture: ShizukuCapture
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capture = ShizukuCapture(applicationContext)
        capture.onCreate()
        setContentView(buildUi())
        scope.launch {
            capture.state.collect { status.text = "Shizuku: ${it::class.simpleName}" }
        }
        maybeRequestNotifications()
        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCommand(intent)
    }

    private fun handleCommand(intent: Intent?) {
        when (intent?.getStringExtra("cmd")) {
            "dsp" -> runDspTest()
            "capture" -> startCapture(
                bypass = intent.getBooleanExtra("bypass", false),
                sampleRate = intent.getIntExtra("sr", 48_000),
                float = intent.getBooleanExtra("float", true),
            )
            "status" -> sendStatus()
            "stop" -> stopCapture()
            "shizuku" -> capture.requestPermission()
        }
    }

    private fun runDspTest() {
        scope.launch(Dispatchers.Default) { runCatching { DspSelfTest.run() }.onFailure { ProbeLog.error("dsp selftest", it) } }
    }

    private fun startCapture(
        bypass: Boolean,
        sampleRate: Int,
        float: Boolean,
    ) {
        ProbeService.start(this, bypass, sampleRate, float)
    }

    private fun sendStatus() {
        startService(Intent(this, ProbeService::class.java).apply { action = ProbeService.ACTION_STATUS })
    }

    private fun stopCapture() {
        startService(Intent(this, ProbeService::class.java).apply { action = ProbeService.ACTION_STOP })
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun buildUi(): ScrollView {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        status =
            TextView(this).apply {
                text = "ViPER4Shizuku Probe"
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 32)
            }
        root.addView(status)

        fun button(
            label: String,
            action: () -> Unit,
        ) {
            root.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { action() }
                },
            )
        }

        button("1. DSP self-test") { runDspTest() }
        button("2. Grant Shizuku permission") { capture.requestPermission() }
        button("3. Start capture (float 48k)") { startCapture(false, 48_000, true) }
        button("   Start capture (bypass DSP)") { startCapture(true, 48_000, true) }
        button("   Status") { sendStatus() }
        button("4. Stop") { stopCapture() }

        return ScrollView(this).apply { addView(root) }
    }

    override fun onDestroy() {
        capture.onDestroy()
        super.onDestroy()
    }
}
