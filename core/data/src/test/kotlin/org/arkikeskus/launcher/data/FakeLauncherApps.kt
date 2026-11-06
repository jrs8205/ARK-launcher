package org.arkikeskus.launcher.data

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/**
 * Lists an app ([packageName], [label]) in the test device's LauncherApps, installed and enabled, so
 * the app list queries it. The two hidden constructors are the API 33+ shape
 * (`LauncherActivityInfoInternal(ActivityInfo, IncrementalStatesInfo, UserHandle)`); the label is
 * non-localized so no resource lookup runs.
 */
internal fun installFakeApp(context: Context, packageName: String, label: String) {
    val user: UserHandle = Process.myUserHandle()
    val applicationInfo = ApplicationInfo().apply {
        this.packageName = packageName
        flags = ApplicationInfo.FLAG_INSTALLED
    }
    val activityInfo = ActivityInfo().apply {
        this.packageName = packageName
        name = "$packageName.Main"
        nonLocalizedLabel = label
        this.applicationInfo = applicationInfo
    }
    val internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal")
    val internal: Any = ReflectionHelpers.callConstructor(
        internalClass,
        from(ActivityInfo::class.java, activityInfo),
        from(Class.forName("android.content.pm.IncrementalStatesInfo"), null),
        from(UserHandle::class.java, user),
    )
    val info: LauncherActivityInfo = ReflectionHelpers.callConstructor(
        LauncherActivityInfo::class.java,
        from(Context::class.java, context),
        from(internalClass, internal),
    )
    val launcherApps = shadowOf(context.getSystemService(LauncherApps::class.java))
    launcherApps.addApplicationInfo(user, packageName, applicationInfo)
    launcherApps.addEnabledPackage(user, packageName)
    launcherApps.addActivity(user, info)
}
