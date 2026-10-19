package dev.theiceji.viper4shizuku.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.theiceji.viper4shizuku.data.repository.ViperRepository
import dev.theiceji.viper4shizuku.service.ViperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only bridge for the device-test scripts. Forwards adb-broadcast commands to the already
 * running (non-exported) [ViperService], e.g.
 *
 *   adb shell am broadcast -n dev.theiceji.viper4shizuku/.debug.EngineTestReceiver \
 *       -e cmd play -e uri file:///sdcard/Android/data/dev.theiceji.viper4shizuku/files/tone.wav
 *   adb shell am broadcast -n dev.theiceji.viper4shizuku/.debug.EngineTestReceiver -e cmd master-on
 *   adb shell am broadcast -n dev.theiceji.viper4shizuku/.debug.EngineTestReceiver \
 *       -e cmd capture-start
 */
class EngineTestReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RepositoryEntryPoint {
        fun repository(): ViperRepository
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val svc = Intent(context, ViperService::class.java)
        when (intent.getStringExtra("cmd")) {
            "play" -> {
                svc.action = ViperService.ACTION_PLAY_URI
                svc.putExtra(ViperService.EXTRA_URI, intent.getStringExtra("uri"))
            }
            "player-stop" -> svc.action = ViperService.ACTION_STOP_PLAYER
            "capture-start" -> svc.action = ViperService.ACTION_START_CAPTURE
            "capture-stop" -> svc.action = ViperService.ACTION_STOP_CAPTURE
            "status" -> svc.action = ViperService.ACTION_LOG_STATUS
            "master-on" -> return toggleMaster(context, svc, true)
            "master-off" -> return toggleMaster(context, svc, false)
            else -> return
        }
        // The service is already a running foreground service, so a plain startService delivers
        // the action without a background-FGS-start violation.
        context.startService(svc)
    }

    /**
     * Like the QS tile: store the switch first, so the UI (which reads the preference) shows what
     * the engines do, then tell the service.
     */
    private fun toggleMaster(
        context: Context,
        svc: Intent,
        enabled: Boolean,
    ) {
        svc.action = ViperService.ACTION_TOGGLE_MASTER
        svc.putExtra(ViperService.EXTRA_MASTER_ENABLED, enabled)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EntryPointAccessors
                    .fromApplication(context, RepositoryEntryPoint::class.java)
                    .repository()
                    .setBooleanPreference(ViperRepository.PREF_MASTER_ENABLE, enabled)
                context.startService(svc)
            } finally {
                pending.finish()
            }
        }
    }
}
