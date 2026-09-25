package app.lawnchair.privatespace

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.android.launcher3.Flags
import com.android.launcher3.util.ApiWrapper
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR

/**
 * Helpers shared between the Private Space header in the app drawer
 * ([com.android.launcher3.allapps.PrivateProfileManager]) and the Private Space
 * lock/unlock widget ([PrivateSpaceToggleWidgetProvider]).
 */
object PrivateSpaceUtils {

    private const val TAG = "PrivateSpaceUtils"

    /**
     * Returns the [UserHandle] of the private profile, or null when Private Space is
     * disabled or not set up on this device.
     *
     * Deliberately performs a direct binder query instead of using
     * [com.android.launcher3.pm.UserCache]: its in-memory map is populated
     * asynchronously on first access, so reads right after process start (fresh
     * install, reboot) see an empty cache and miss the private profile — which
     * previously left the widget rendered without a click handler until the first
     * app drawer toggle warmed the cache.
     *
     * Safe to call from any thread; does binder calls.
     */
    @JvmStatic
    fun getPrivateProfileUserHandle(context: Context): UserHandle? {
        // Private Space and its profile user type only exist on Android 15+.
        if (!Flags.enablePrivateSpace() ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
        ) {
            return null
        }
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        return try {
            context.getSystemService(UserManager::class.java)?.userProfiles
                ?.firstOrNull { user ->
                    launcherApps.getLauncherUserInfo(user)?.userType ==
                        UserManager.USER_TYPE_PROFILE_PRIVATE
                }
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot resolve private profile user", t)
            null
        }
    }

    /** Returns whether the private profile is currently unlocked (quiet mode disabled). */
    @JvmStatic
    fun isPrivateSpaceUnlocked(userManager: UserManager, user: UserHandle): Boolean =
        !userManager.isQuietModeEnabled(user)

    /**
     * Mirrors [com.android.launcher3.allapps.PrivateProfileManager.isPrivateSpaceHiddenWhenLocked]:
     * whether the system setting hides the Private Space entry point while locked. When
     * this is set and Private Space is locked, the app drawer hides the section entirely,
     * so the widget reports itself unavailable too.
     */
    @JvmStatic
    fun isPrivateSpaceHiddenWhenLocked(context: Context): Boolean = try {
        Settings.Secure.getInt(
            context.contentResolver,
            "hide_privatespace_entry_point",
            0,
        ) != 0
    } catch (t: Throwable) {
        Log.e(TAG, "Cannot read hide_privatespace_entry_point setting", t)
        false
    }

    /**
     * Enables or disables quiet mode (locks/unlocks Private Space) on a worker thread.
     * Resolves the private profile itself, so it works regardless of any prior state.
     */
    @JvmStatic
    fun setQuietMode(context: Context, enable: Boolean) {
        UI_HELPER_EXECUTOR.post {
            val user = getPrivateProfileUserHandle(context) ?: return@post
            setQuietModeSafely(
                context,
                context.getSystemService(UserManager::class.java),
                user,
                enable,
            )
        }
    }

    /**
     * Sets quiet mode for the private profile.
     * If [SecurityException] is thrown, prompts the user to set this launcher as HOME app,
     * mirroring [com.android.launcher3.allapps.PrivateProfileManager.setQuietModeSafely].
     */
    @JvmStatic
    fun setQuietModeSafely(
        context: Context,
        userManager: UserManager?,
        user: UserHandle,
        enable: Boolean,
    ) {
        try {
            userManager?.requestQuietModeEnabled(enable, user)
        } catch (ex: SecurityException) {
            // Quiet mode can only be toggled by the default home app. ApiWrapper must be
            // accessed on the main thread, so hop over before showing the role prompt.
            MAIN_EXECUTOR.post {
                try {
                    ApiWrapper.INSTANCE.get(context).assignDefaultHomeRole(context)
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Cannot request quiet mode nor assign home role", fallbackEx)
                }
            }
        }
    }
}
