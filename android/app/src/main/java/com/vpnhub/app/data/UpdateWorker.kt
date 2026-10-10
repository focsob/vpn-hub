package com.vpnhub.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vpnhub.app.vpn.VpnController
import com.vpnhub.app.vpn.VpnState
import java.util.concurrent.TimeUnit

/** Every 15 minutes (Android's minimum) in the background; pull the freshly tested list and, if connected, swap dead nodes out of the tunnel. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Prefs.autoUpdate) return Result.success()
        val result = NodeRepository.refresh()
        if (result.isSuccess && VpnState.status.value == VpnState.Status.Connected) {
            VpnController.reload(applicationContext)
        }
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "hourly-node-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
