package com.gm.autoclick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Recebe o resultado da sessão de instalação do [Updater]. Não exportado. */
class UpdateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.gm.autoclick.UPDATE_STATUS"
        const val EXTRA_CODE = "code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        Updater.onInstallStatus(context.applicationContext, st, msg, confirm)
    }
}
