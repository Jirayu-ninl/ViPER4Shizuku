package dev.theiceji.viper4shizuku.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.theiceji.viper4shizuku.data.repository.ViperRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MasterTileService : TileService() {
    @Inject
    lateinit var repository: ViperRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val enabled = repository.getBooleanPreference(ViperRepository.PREF_MASTER_ENABLE).first()
            render(enabled)
        }
    }

    override fun onClick() {
        super.onClick()
        val next = (qsTile?.state != Tile.STATE_ACTIVE)
        render(next)
        scope.launch {
            repository.setBooleanPreference(ViperRepository.PREF_MASTER_ENABLE, next)
            ViperService.toggleMaster(this@MasterTileService, next)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
