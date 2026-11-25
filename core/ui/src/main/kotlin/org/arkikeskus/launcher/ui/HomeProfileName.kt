package org.arkikeskus.launcher.ui

import android.content.res.Resources

/** [name] as a profile is shown everywhere: one the user never named has an empty stored name. */
fun shownProfileName(res: Resources, name: String): String =
    name.ifBlank { res.getString(R.string.profile_default_name) }
