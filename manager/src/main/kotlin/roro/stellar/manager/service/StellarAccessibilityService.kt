package roro.stellar.manager.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import roro.stellar.manager.AppConstants
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.ktx.setComponentEnabled
import roro.stellar.manager.receiver.BootCompleteReceiver

class StellarAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (StellarSettings.getBootMode() != StellarSettings.BootMode.ACCESSIBILITY) return
        try {
            val componentName = ComponentName(this, BootCompleteReceiver::class.java)
            packageManager.setComponentEnabled(componentName, true)
            Log.i(AppConstants.TAG, "Accessibility mode: BootCompleteReceiver enabled")
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Failed to enable BootCompleteReceiver via accessibility", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
}
