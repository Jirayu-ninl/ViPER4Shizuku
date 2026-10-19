package dev.theiceji.viper4shizuku.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import dev.theiceji.viper4shizuku.utils.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

class AudioOutputDetector(
    context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val _activeDevice = MutableStateFlow(detectActiveDevice(audioManager))
    val activeDevice: StateFlow<AudioDevice> = _activeDevice.asStateFlow()

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val connected = checkHeadphoneConnected(audioManager)
                val device = detectActiveDevice(audioManager)
                FileLogger.i(
                    "AudioOutput",
                    "Output device added: headphone=${if (connected) "connected" else "disconnected"} device=${device.name}",
                )
                _activeDevice.value = device
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val connected = checkHeadphoneConnected(audioManager)
                val device = detectActiveDevice(audioManager)
                FileLogger.i(
                    "AudioOutput",
                    "Output device removed: headphone=${if (connected) "connected" else "disconnected"} device=${device.name}",
                )
                _activeDevice.value = device
            }
        }

    init {
        val initialDevice = _activeDevice.value
        FileLogger.i(
            "AudioOutput",
            "Output init: headphone=${if (initialDevice.isHeadphone) "connected" else "disconnected"} device=${initialDevice.name}",
        )
        audioManager.registerAudioDeviceCallback(callback, handler)
        detectors += this
    }

    fun stop() {
        detectors -= this
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    /** The fields of an [AudioDeviceInfo] the classifier reads, as plain values. */
    @VisibleForTesting
    internal data class Output(
        val type: Int,
        val address: String,
        val productName: String?,
        val id: Int,
    )

    @Suppress("NewApi")
    companion object {
        private val HEADPHONE_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )

        private val BT_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )

        private val USB_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
            )

        private val AUTO_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                AudioDeviceInfo.TYPE_BUS,
            )

        /** A LOOP_BACK mix's address: its policy's registration id, this, then the mix index. */
        private const val MIX_ADDRESS_INFIX = "mixp:"

        private val detectors = CopyOnWriteArraySet<AudioOutputDetector>()

        @Volatile
        private var ownPolicyRegistrations: Set<String> = emptySet()

        /**
         * The capture's LOOP_BACK policy adds a remote-submix output while it is registered. That
         * output is ViPER4Shizuku itself (its own track still plays to the real device), so it
         * must not be taken for Android Auto and switch the profile. [registration] is the
         * policy's registration id.
         *
         * Registrations are never forgotten: the mix can outlive the capture by a few hundred ms
         * (when Shizuku dies, the app hears it before AudioService releases the policy), and
         * AudioService numbers registrations per boot, so no other policy reuses the id.
         */
        fun addOwnPolicyRegistration(registration: String) {
            if (registration in ownPolicyRegistrations) return
            ownPolicyRegistrations = ownPolicyRegistrations + registration
            FileLogger.i("AudioOutput", "Own policy registration: $registration")
            for (detector in detectors) {
                detector.handler.post {
                    detector._activeDevice.value = detectActiveDevice(detector.audioManager)
                }
            }
        }

        private fun checkHeadphoneConnected(audioManager: AudioManager): Boolean =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type in HEADPHONE_TYPES }

        private fun detectActiveDevice(audioManager: AudioManager): AudioDevice {
            val mediaAttrs =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            val navigationAttrs =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .build()

            val outputs =
                selectOutputs(
                    routedOutputs = routedOutputs(audioManager, mediaAttrs),
                    ownPolicyRegistrations = ownPolicyRegistrations,
                    mediaStrategyOutputs = { routedOutputs(audioManager, navigationAttrs) },
                ) {
                    audioManager
                        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .filter { it.isSink }
                        .map(::toOutput)
                }

            for (dev in outputs) {
                FileLogger.d(
                    "AudioOutput",
                    "  output: type=${dev.type} name=${dev.productName} addr=${dev.address} id=${dev.id}",
                )
            }

            return classify(outputs)
        }

        /** Where [attrs] play now; empty below API 33, which cannot tell. */
        private fun routedOutputs(
            audioManager: AudioManager,
            attrs: AudioAttributes,
        ): List<Output> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                audioManager.getAudioDevicesForAttributes(attrs).filter { it.isSink }.map(::toOutput)
            } else {
                emptyList()
            }

        private fun toOutput(dev: AudioDeviceInfo): Output =
            Output(
                type = dev.type,
                address = dev.address,
                productName = dev.productName?.toString(),
                id = dev.id,
            )

        @VisibleForTesting
        internal fun isOwnMix(
            output: Output,
            ownPolicyRegistrations: Set<String>,
        ): Boolean =
            output.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX &&
                ownPolicyRegistrations.any { output.address.startsWith(it + MIX_ADDRESS_INFIX) }

        /**
         * The outputs media plays to, without the capture's own mix. While capture runs, media is
         * routed only to that mix. Navigation guidance shares the media strategy but not the
         * mix's rule, so its routing ([mediaStrategyOutputs]) stands in. Failing that, as below
         * API 33, the device list does, without Bluetooth SCO: it carries calls, never media, and
         * a headset lists it beside its A2DP output.
         */
        @VisibleForTesting
        internal fun selectOutputs(
            routedOutputs: List<Output>,
            ownPolicyRegistrations: Set<String>,
            mediaStrategyOutputs: () -> List<Output>,
            allOutputs: () -> List<Output>,
        ): List<Output> {
            fun List<Output>.withoutOwnMix() = filterNot { isOwnMix(it, ownPolicyRegistrations) }
            return routedOutputs
                .withoutOwnMix()
                .ifEmpty { mediaStrategyOutputs().withoutOwnMix() }
                .ifEmpty {
                    allOutputs().withoutOwnMix().filterNot { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                }
        }

        @VisibleForTesting
        internal fun classify(outputs: List<Output>): AudioDevice {
            val androidAuto = outputs.firstOrNull { it.type in AUTO_TYPES }
            if (androidAuto != null) {
                return AudioDevice(
                    id = AudioDevice.ID_ANDROID_AUTO,
                    name = "Android Auto",
                    type = androidAuto.type,
                    isHeadphone = false,
                )
            }

            val headphone = outputs.firstOrNull { it.type in HEADPHONE_TYPES }
            if (headphone != null) {
                val isBt = headphone.type in BT_TYPES
                val productName = headphone.productName?.takeIf { it.isNotBlank() }
                return if (isBt) {
                    val address =
                        headphone.address.takeIf { it.isNotBlank() } ?: "bt_${headphone.id}"
                    AudioDevice(
                        id = address,
                        name = productName ?: getTypeName(headphone.type),
                        type = headphone.type,
                        isHeadphone = true,
                    )
                } else if (headphone.type in USB_TYPES) {
                    val address =
                        headphone.address.takeIf { it.isNotBlank() } ?: "usb_${headphone.id}"
                    AudioDevice(
                        id = address,
                        name = productName ?: "USB Audio",
                        type = headphone.type,
                        isHeadphone = true,
                    )
                } else {
                    AudioDevice(
                        id = AudioDevice.ID_WIRED,
                        name = productName ?: "Wired Headphone",
                        type = headphone.type,
                        isHeadphone = true,
                    )
                }
            }

            return AudioDevice.DEFAULT_SPEAKER
        }

        private fun getTypeName(type: Int): String =
            when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE Broadcast"
                AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
                else -> "Bluetooth"
            }
    }
}
