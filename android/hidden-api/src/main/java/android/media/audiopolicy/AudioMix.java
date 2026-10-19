package android.media.audiopolicy;

import android.media.AudioFormat;

/** Stub of the @SystemApi class (AOSP android-16.0.0_r1). Only the members ViPER4Shizuku uses. */
public class AudioMix {
    public static final int ROUTE_FLAG_RENDER = 0x1;
    public static final int ROUTE_FLAG_LOOP_BACK = 0x1 << 1;

    public static final int MIX_STATE_DISABLED = -1;
    public static final int MIX_STATE_IDLE = 0;
    public static final int MIX_STATE_MIXING = 1;

    private AudioMix() {
        throw new RuntimeException("Stub!");
    }

    public int getMixState() {
        throw new RuntimeException("Stub!");
    }

    public int getRouteFlags() {
        throw new RuntimeException("Stub!");
    }

    public AudioFormat getFormat() {
        throw new RuntimeException("Stub!");
    }

    /** @hide in AOSP; returns the remote submix address assigned at registration. */
    public String getRegistration() {
        throw new RuntimeException("Stub!");
    }

    public static class Builder {
        public Builder(AudioMixingRule rule) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        public Builder setFormat(AudioFormat format) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        public Builder setRouteFlags(int routeFlags) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        public AudioMix build() throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }
    }
}
