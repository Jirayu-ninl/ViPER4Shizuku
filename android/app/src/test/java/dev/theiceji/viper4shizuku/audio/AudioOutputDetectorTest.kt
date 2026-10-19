package dev.theiceji.viper4shizuku.audio

import android.media.AudioDeviceInfo
import dev.theiceji.viper4shizuku.audio.AudioOutputDetector.Companion.classify
import dev.theiceji.viper4shizuku.audio.AudioOutputDetector.Companion.isOwnMix
import dev.theiceji.viper4shizuku.audio.AudioOutputDetector.Companion.selectOutputs
import dev.theiceji.viper4shizuku.audio.AudioOutputDetector.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputDetectorTest {
    // Seen on a real Android 16 phone when capture registered its policy.
    private val registration = "1806151:ap:8"
    private val ownMix = submix("${registration}mixp:0", id = 208)

    private val speaker = Output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Speaker", 2)
    private val earpiece = Output(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "", "Earpiece", 1)
    private val buds = Output(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB:CC:DD:EE:FF", "Buds", 30)
    private val scoOfBuds = Output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "XX:XX:XX:XX:00:00", "Phone", 31)

    private fun submix(
        address: String,
        id: Int = 300,
    ) = Output(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, address, "remote-submix", id)

    private fun detect(
        routed: List<Output>,
        all: List<Output>,
        ownRegistrations: Set<String> = setOf(registration),
        mediaStrategy: List<Output> = emptyList(),
    ): AudioDevice = classify(selectOutputs(routed, ownRegistrations, { mediaStrategy }) { all })

    @Test
    fun ownMixIsNotAndroidAuto() {
        val device = detect(routed = listOf(ownMix), all = listOf(earpiece, speaker, ownMix))
        assertEquals(AudioDevice.ID_SPEAKER, device.id)
    }

    @Test
    fun ownMixFallsBackToConnectedHeadphones() {
        val device = detect(routed = listOf(ownMix), all = listOf(speaker, ownMix, buds))
        assertEquals("AA:BB:CC:DD:EE:FF", device.id)
        assertTrue(device.isHeadphone)
    }

    @Test
    fun ownMixUsesTheMediaStrategyDevice() {
        var listed = 0
        val outputs =
            selectOutputs(listOf(ownMix), setOf(registration), { listOf(buds) }) {
                listed++
                listOf(speaker, scoOfBuds, buds, ownMix)
            }
        assertEquals("AA:BB:CC:DD:EE:FF", classify(outputs).id)
        assertEquals(0, listed)
    }

    @Test
    fun deviceListSkipsBluetoothSco() {
        // Seen on a phone with Bluetooth earbuds connected during capture: the list has the
        // headset's SCO output (id 420) before its A2DP output (id 411).
        val device = detect(routed = listOf(ownMix), all = listOf(speaker, scoOfBuds, buds, ownMix))
        assertEquals("AA:BB:CC:DD:EE:FF", device.id)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, device.type)
    }

    @Test
    fun scoOnlyHeadsetDoesNotTakeMedia() {
        val device = detect(routed = listOf(ownMix), all = listOf(speaker, scoOfBuds, ownMix))
        assertEquals(AudioDevice.ID_SPEAKER, device.id)
    }

    @Test
    fun routedDeviceBesideOwnMixWins() {
        val device = detect(routed = listOf(ownMix, buds), all = listOf(speaker, ownMix, buds))
        assertEquals("AA:BB:CC:DD:EE:FF", device.id)
    }

    @Test
    fun foreignSubmixIsStillAndroidAuto() {
        val device = detect(routed = listOf(submix("0")), all = listOf(speaker, submix("0"), ownMix))
        assertEquals(AudioDevice.ID_ANDROID_AUTO, device.id)
    }

    @Test
    fun otherPolicyMixIsStillAndroidAuto() {
        val other = submix("4242:ap:9mixp:0")
        assertEquals(AudioDevice.ID_ANDROID_AUTO, detect(routed = listOf(other), all = listOf(speaker, other)).id)
        assertEquals(AudioDevice.ID_ANDROID_AUTO, detect(routed = listOf(ownMix), all = listOf(speaker, other)).id)
    }

    @Test
    fun busIsStillAndroidAuto() {
        val bus = Output(AudioDeviceInfo.TYPE_BUS, "bus0_media_out", "bus", 40)
        assertEquals(AudioDevice.ID_ANDROID_AUTO, detect(routed = listOf(bus), all = listOf(bus, ownMix)).id)
    }

    @Test
    fun withoutRegistrationEverySubmixIsAndroidAuto() {
        val device = detect(routed = listOf(ownMix), all = listOf(speaker, ownMix), ownRegistrations = emptySet())
        assertEquals(AudioDevice.ID_ANDROID_AUTO, device.id)
    }

    @Test
    fun earlierCaptureMixThatOutlivesItIsStillOwn() {
        // Seen on a real device: Shizuku died, the app stopped capturing, and the mix of policy
        // 28261138:ap:12 stayed routed for ~190 ms more. Later captures add their ids too.
        val earlier = "28261138:ap:12"
        val lingering = submix("${earlier}mixp:0", id = 335)
        val device =
            detect(
                routed = listOf(lingering),
                all = listOf(speaker, lingering),
                ownRegistrations = setOf(earlier, "-3944910:ap:13"),
            )
        assertEquals(AudioDevice.ID_SPEAKER, device.id)
    }

    @Test
    fun ownMixMatchesOnlyItsRegistration() {
        val own = setOf(registration)
        assertTrue(isOwnMix(ownMix, own))
        assertTrue(isOwnMix(submix("${registration}mixp:1"), own))
        // Registration ids can start with a minus sign (seen on a real device).
        assertTrue(isOwnMix(submix("-3944910:ap:13mixp:0"), setOf("-3944910:ap:13")))
        // Another policy whose id starts with ours.
        assertFalse(isOwnMix(submix("1806151:ap:80mixp:0"), own))
        assertFalse(isOwnMix(submix("0"), own))
        assertFalse(isOwnMix(ownMix, emptySet()))
        assertFalse(isOwnMix(ownMix.copy(type = AudioDeviceInfo.TYPE_BUS), own))
    }

    @Test
    fun nothingElseIsReadWhileRoutingHasADevice() {
        var read = 0
        selectOutputs(
            listOf(speaker),
            setOf(registration),
            {
                read++
                listOf(speaker)
            },
        ) {
            read++
            listOf(speaker)
        }
        assertEquals(0, read)
    }
}
