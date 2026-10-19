package dev.theiceji.viper4shizuku.capture;

/** Callbacks from the capture service (shell uid) back to the app. */
interface ICaptureCallback {
    /** Capture stopped on its own (error, policy loss, or app-death teardown). */
    oneway void onStopped(String reason) = 1;

    /** AudioPolicy mix state changed: -1 disabled, 0 idle, 1 mixing. */
    oneway void onMixStateChanged(int state) = 2;
}
