# The user-service class is instantiated by Shizuku in the shell process by name.
-keep class dev.theiceji.viper4shizuku.capture.CaptureUserService { *; }
# AIDL stubs and the Bundle-backed DTOs cross the binder.
-keep class dev.theiceji.viper4shizuku.capture.ICaptureService { *; }
-keep class dev.theiceji.viper4shizuku.capture.ICaptureService$Stub { *; }
-keep class dev.theiceji.viper4shizuku.capture.ICaptureCallback { *; }
-keep class dev.theiceji.viper4shizuku.capture.ICaptureCallback$Stub { *; }
# Shizuku provider is referenced from the merged manifest.
-keep class rikka.shizuku.ShizukuProvider { *; }
# @SystemApi framework classes, compiled against :hidden-api stubs: absent from the public
# android.jar R8 sees, present at runtime in the shell-uid process.
-dontwarn android.media.audiopolicy.**
# R8 cannot see that these override the framework listener, so it would drop them as unused.
-keepclassmembers class dev.theiceji.viper4shizuku.capture.** {
    public void onStatusChange();
    public void onMixStateUpdate(android.media.audiopolicy.AudioMix);
}
