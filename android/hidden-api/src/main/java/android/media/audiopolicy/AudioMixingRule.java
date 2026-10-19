package android.media.audiopolicy;

/** Stub of the @SystemApi class (AOSP android-16.0.0_r1). Only the members ViPER4Shizuku uses. */
public class AudioMixingRule {
    public static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    public static final int RULE_MATCH_UID = 0x1 << 2;
    public static final int RULE_MATCH_USERID = 0x1 << 3;

    public static final int MIX_ROLE_PLAYERS = 0;

    private AudioMixingRule() {
        throw new RuntimeException("Stub!");
    }

    public int getTargetMixRole() {
        throw new RuntimeException("Stub!");
    }

    public static class Builder {
        public Builder() {
            throw new RuntimeException("Stub!");
        }

        public Builder addMixRule(int rule, Object property) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        public Builder excludeMixRule(int rule, Object property) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        public Builder setTargetMixRole(int mixRole) {
            throw new RuntimeException("Stub!");
        }

        public AudioMixingRule build() {
            throw new RuntimeException("Stub!");
        }
    }
}
