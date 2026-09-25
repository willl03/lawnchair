package app.lawnchair.privatespace

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.widget.RemoteViews
import com.android.launcher3.BuildConfig
import com.android.launcher3.Flags
import com.android.launcher3.R
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR

/**
 * Home screen widget that locks/unlocks Private Space, mirroring the toggle in the
 * Private Space header of the app drawer. Shows the action that will be performed:
 * "Lock" while Private Space is unlocked, "Unlock" while it is locked.
 */
class PrivateSpaceToggleWidgetProvider : AppWidgetProvider() {

    /** Render states: QS-tile style active (unlocked), inactive (locked), unavailable. */
    private enum class WidgetState {
        UNAVAILABLE,
        UNLOCKED,
        LOCKED,
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        renderAll(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action == ACTION_LOCK || action == ACTION_UNLOCK) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (action == ACTION_LOCK && appWidgetIds.isNotEmpty()) {
                // Optimistic render for locking only: locking never requires
                // authentication and takes effect immediately, so the assumed state is
                // (almost) always correct. Unlocking may require auth, so it only
                // renders once the real state changed — no bright flash while the
                // auth prompt is up.
                appWidgetManager.updateAppWidget(
                    appWidgetIds,
                    createRemoteViews(context, WidgetState.LOCKED),
                )
            }
            if (appWidgetIds.isNotEmpty()) {
                // Re-read the real state shortly after; covers unlocks that take effect
                // without a profile broadcast reaching us yet, and corrects the lock
                // optimistic render if the request was rejected.
                MAIN_EXECUTOR.handler.postDelayed(
                    { renderAll(context, appWidgetManager.getAppWidgetIds(componentName)) },
                    VERIFY_DELAY_MS,
                )
            }
            PrivateSpaceUtils.setQuietMode(context, action == ACTION_LOCK)
        } else if (action != AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            // Quiet mode changed outside this widget (drawer toggle, Settings, system
            // auth): re-render with the new state. APPWIDGET_UPDATE is already handled
            // by onUpdate() via super.
            renderAll(context, AppWidgetManager.getInstance(context).getAppWidgetIds(componentName))
        }
    }

    /** Re-renders every placed Private Space widget with the current lock state. */
    fun renderAll(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) {
            return
        }
        val appWidgetManager = AppWidgetManager.getInstance(context)
        UI_HELPER_EXECUTOR.execute {
            if (!Flags.enablePrivateSpace()) {
                return@execute
            }
            val userManager = context.getSystemService(UserManager::class.java)
            val user = PrivateSpaceUtils.getPrivateProfileUserHandle(context)
            val unlocked = user != null && userManager != null &&
                PrivateSpaceUtils.isPrivateSpaceUnlocked(userManager, user)
            // Same visibility rule as the app drawer: hide the entry point while locked.
            val hiddenWhileLocked = !unlocked &&
                PrivateSpaceUtils.isPrivateSpaceHiddenWhenLocked(context)
            val state = when {
                user == null || userManager == null -> WidgetState.UNAVAILABLE
                hiddenWhileLocked -> WidgetState.UNAVAILABLE
                unlocked -> WidgetState.UNLOCKED
                else -> WidgetState.LOCKED
            }
            val views = createRemoteViews(context, state)
            appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
        }
    }

    private fun createRemoteViews(context: Context, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.private_space_toggle_widget)
        when (state) {
            WidgetState.UNAVAILABLE -> {
                views.setInt(
                    R.id.widget_pill,
                    "setBackgroundResource",
                    R.drawable.ps_widget_pill_background_inactive,
                )
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_ps_widget_unlock)
                views.setTextViewText(
                    R.id.widget_label,
                    context.getString(R.string.ps_widget_unavailable),
                )
                views.setTextColor(
                    R.id.widget_label,
                    context.getColor(R.color.material_color_on_surface_variant),
                )
                views.setContentDescription(
                    R.id.widget_container,
                    context.getString(R.string.ps_widget_unavailable_description),
                )
            }
            WidgetState.UNLOCKED -> {
                views.setInt(
                    R.id.widget_pill,
                    "setBackgroundResource",
                    R.drawable.ps_widget_pill_background,
                )
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_ps_widget_lock)
                views.setTextViewText(
                    R.id.widget_label,
                    context.getString(R.string.ps_container_lock_title),
                )
                views.setTextColor(
                    R.id.widget_label,
                    context.getColor(R.color.material_color_on_primary_fixed),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_container,
                    createTogglePendingIntent(context, ACTION_LOCK),
                )
                views.setContentDescription(
                    R.id.widget_container,
                    context.getString(R.string.ps_container_unlock_button_content_description),
                )
            }
            WidgetState.LOCKED -> {
                views.setInt(
                    R.id.widget_pill,
                    "setBackgroundResource",
                    R.drawable.ps_widget_pill_background_inactive,
                )
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_ps_widget_unlock)
                views.setTextViewText(
                    R.id.widget_label,
                    context.getString(R.string.ps_widget_unlock_title),
                )
                views.setTextColor(
                    R.id.widget_label,
                    context.getColor(R.color.material_color_on_surface_variant),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_container,
                    createTogglePendingIntent(context, ACTION_UNLOCK),
                )
                views.setContentDescription(
                    R.id.widget_container,
                    context.getString(R.string.ps_container_lock_button_content_description),
                )
            }
        }
        return views
    }

    private fun createTogglePendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(action)
            .setComponent(componentName)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val ACTION_LOCK = "app.lawnchair.privatespace.ACTION_LOCK"
        private const val ACTION_UNLOCK = "app.lawnchair.privatespace.ACTION_UNLOCK"

        private const val VERIFY_DELAY_MS = 500L

        @JvmField
        val componentName = ComponentName(
            BuildConfig.APPLICATION_ID,
            PrivateSpaceToggleWidgetProvider::class.java.name,
        )

        /** Forces a refresh of all placed widgets, e.g. when the launcher state changes. */
        @JvmStatic
        fun pushUpdate(context: Context) {
            if (!Flags.enablePrivateSpace()) {
                return
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            PrivateSpaceToggleWidgetProvider()
                .renderAll(context, appWidgetManager.getAppWidgetIds(componentName))
        }
    }
}
