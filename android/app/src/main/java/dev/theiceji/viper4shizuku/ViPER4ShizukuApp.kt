package dev.theiceji.viper4shizuku

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import dev.theiceji.viper4shizuku.utils.FileLogger

const val SERVICE_CHANNEL_ID = "viper4shizuku_service"
const val BULK_OP_CHANNEL_ID = "viper4shizuku_bulk_op"

@HiltAndroidApp
class ViPER4ShizukuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        FileLogger.init(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                BULK_OP_CHANNEL_ID,
                getString(R.string.notification_bulk_op_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_bulk_op_channel_description)
                setShowBadge(false)
            },
        )
    }
}
