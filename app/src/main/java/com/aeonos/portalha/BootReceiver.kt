package com.aeonos.portalha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            // fleet: after an update installed without adb nothing relaunches us (camera + MQTT down
            // until a reboot) - restart the service and bring the dashboard back the same way.
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Tell the service this was a boot, so it can also put the dashboard on screen —
            // starting the service alone leaves the Portal sitting on the launcher after a power
            // cut, which is not what a wall panel is for. The service does it on a delay because
            // the launcher asserts HOME as the system finishes booting and would land on top of
            // anything we started here.
            BridgeService.startFromBoot(context)
        }
    }
}
