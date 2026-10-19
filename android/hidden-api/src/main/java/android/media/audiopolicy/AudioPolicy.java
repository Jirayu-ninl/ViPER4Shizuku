package android.media.audiopolicy;

import android.content.Context;
import android.media.AudioRecord;
import android.os.Looper;

import java.util.List;

/** Stub of the @SystemApi class (AOSP android-16.0.0_r1). Only the members ViPER4Shizuku uses. */
public class AudioPolicy {
    public static final int POLICY_STATUS_UNREGISTERED = 1;
    public static final int POLICY_STATUS_REGISTERED = 2;

    private AudioPolicy() {
        throw new RuntimeException("Stub!");
    }

    public int getStatus() {
        throw new RuntimeException("Stub!");
    }

    public int updateMixingRules(List<android.util.Pair<AudioMix, AudioMixingRule>> mixingRuleUpdates) {
        throw new RuntimeException("Stub!");
    }

    public AudioRecord createAudioRecordSink(AudioMix mix) throws IllegalArgumentException {
        throw new RuntimeException("Stub!");
    }

    /** @hide in AOSP. */
    public String getRegistration() {
        throw new RuntimeException("Stub!");
    }

    public static class Builder {
        public Builder(Context context) {
            throw new RuntimeException("Stub!");
        }

        public Builder addMix(AudioMix mix) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        public Builder setLooper(Looper looper) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        public void setAudioPolicyStatusListener(AudioPolicyStatusListener l) {
            throw new RuntimeException("Stub!");
        }

        public AudioPolicy build() {
            throw new RuntimeException("Stub!");
        }
    }

    public abstract static class AudioPolicyStatusListener {
        public void onStatusChange() {
        }

        public void onMixStateUpdate(AudioMix mix) {
        }
    }
}
