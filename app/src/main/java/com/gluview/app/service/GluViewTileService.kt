package com.gluview.app.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.gluview.app.alert.AlertManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class GluViewTileService : TileService() {

    @Inject lateinit var alertManager: AlertManager

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (alertManager.isSilenced()) {
            alertManager.clearSilence()
        } else {
            alertManager.silenceFor(2)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        if (alertManager.isSilenced()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Alertas silenciados"
            tile.contentDescription = "Toque para reativar alertas"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "GluView"
            tile.contentDescription = "Toque para silenciar alertas por 2h"
        }
        tile.updateTile()
    }
}
