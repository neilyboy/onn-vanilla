package tv.vanilla.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV always routes the Home button to its own launcher, whatever app holds the HOME role,
 * and removing that launcher sends the box into a setup-recovery loop. This service keeps the stock
 * launcher installed but out of sight: Home presses and stock-home windows bring up Vanilla Home.
 */
class HomeButtonService : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString().orEmpty()
        if (released(this) || visitingStock) return
        if (pkg in STOCK_HOMES && (cls.endsWith("HomeActivity") || cls.endsWith("MainActivity") || cls.endsWith("LauncherActivity"))) goHome()
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME || released(this)) return false
        if (e.action == KeyEvent.ACTION_UP) goHome()
        return true
    }

    private fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() { connected = true }

    override fun onDestroy() { connected = false; super.onDestroy() }

    companion object {
        @Volatile var connected = false

        /** Set while the user deliberately opened the stock home; cleared when Vanilla Home comes back. */
        @Volatile var visitingStock = false

        /**
         * Force-stopping the launcher unbinds this service and Android won't rebind it on its own,
         * which hands Home back to the stock launcher. With WRITE_SECURE_SETTINGS (granted over adb
         * at setup) re-writing the setting makes the system bind it again.
         */
        private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("home", android.content.Context.MODE_PRIVATE)

        /** True when the user handed the Home button back to the device's own home screen. */
        fun released(ctx: android.content.Context) = prefs(ctx).getBoolean("home_released", false)

        fun setReleased(ctx: android.content.Context, on: Boolean) {
            prefs(ctx).edit().putBoolean("home_released", on).apply()
            if (!on) { connected = false; heal(ctx); return }
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val id = "${ctx.packageName}/${HomeButtonService::class.java.name}"
            val r = ctx.contentResolver
            val others = (android.provider.Settings.Secure.getString(r, "enabled_accessibility_services") ?: "")
                .split(':').filter { it.isNotBlank() && it != "null" && it != id }
            runCatching { android.provider.Settings.Secure.putString(r, "enabled_accessibility_services", others.joinToString(":").ifEmpty { null }) }
        }

        /** The device's own home screen (Google TV, Android TV or a maker's launcher), whatever the brand. */
        fun stockHome(ctx: android.content.Context): android.content.ComponentName? {
            val pm = ctx.packageManager
            val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_ALL)
                .map { it.activityInfo }.filter { it.packageName != ctx.packageName && it.packageName != "com.android.settings" && !it.packageName.contains("fallbackhome", true) }
            val pick = homes.minByOrNull { STOCK_HOMES.indexOf(it.packageName).let { i -> if (i < 0) STOCK_HOMES.size else i } } ?: return null
            return android.content.ComponentName(pick.packageName, pick.name)
        }

        fun openStockHome(ctx: android.content.Context): Boolean {
            val c = stockHome(ctx) ?: return false
            visitingStock = true
            return runCatching {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }

        fun heal(ctx: android.content.Context) {
            if (connected || released(ctx)) return
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val id = "${ctx.packageName}/${HomeButtonService::class.java.name}"
            val r = ctx.contentResolver
            val others = (android.provider.Settings.Secure.getString(r, "enabled_accessibility_services") ?: "")
                .split(':').filter { it.isNotBlank() && it != "null" && it != id }
            runCatching {
                android.provider.Settings.Secure.putString(r, "enabled_accessibility_services", others.joinToString(":").ifEmpty { null })
                android.provider.Settings.Secure.putString(r, "enabled_accessibility_services", (others + id).joinToString(":"))
                android.provider.Settings.Secure.putInt(r, "accessibility_enabled", 1)
            }
        }

        val STOCK_HOMES = listOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            "com.amazon.tv.launcher",
        )
    }
}
