package dev.theiceji.viper4shizuku.capture;

import android.os.ParcelFileDescriptor;
import dev.theiceji.viper4shizuku.capture.ICaptureCallback;

/**
 * Runs in the Shizuku user-service process (uid 2000 = com.android.shell).
 * That uid holds MODIFY_AUDIO_ROUTING, so it can register a LOOP_BACK-only AudioPolicy that
 * reroutes app playback into a remote-submix the app then reads and processes.
 */
interface ICaptureService {
    /** Shizuku calls this when it destroys the service; must undo everything. */
    void destroy() = 16777114;

    /** uid this process runs as; expected to be 2000 (shell). */
    int getUid() = 1;

    /**
     * Registers the loopback policy and starts pumping captured PCM into the returned pipe's
     * write end; the caller reads the read end. [config] is a CaptureConfig as a Bundle.
     * [excludedUids] are kept out of the mix (the ViPER4Shizuku uid plus any user exclusions), so
     * their audio plays normally. [appBinder] is linked-to-death: if the app dies, the policy
     * is torn down automatically. Returns the read end, or throws on failure.
     */
    ParcelFileDescriptor start(in android.os.Bundle config, in int[] excludedUids,
                               IBinder appBinder, ICaptureCallback callback) = 2;

    /** Tears down the policy and stops capture. Safe to call twice. */
    void stop() = 3;

    boolean isCapturing() = 4;

    /** Snapshot of capture state as a Bundle (see CaptureStatus). */
    android.os.Bundle getStatus() = 5;

    /** Replace the excluded-uid set without stopping (re-registers the mixing rule). */
    void updateExcludedUids(in int[] excludedUids) = 6;
}
