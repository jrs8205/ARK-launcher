package org.arkikeskus.launcher.feature.settings

import java.lang.reflect.Proxy

/**
 * A [SettingsActions] for tests that only notes what was asked of it: each call's function name and
 * arguments, in order. Built as a proxy so a new action needs nothing here.
 */
internal class RecordedActions {
    val calls = mutableListOf<Pair<String, List<Any?>>>()

    val actions: SettingsActions = Proxy.newProxyInstance(
        SettingsActions::class.java.classLoader,
        arrayOf(SettingsActions::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RecordedActions"
            else -> {
                calls += method.name to args.orEmpty().toList()
                null
            }
        }
    } as SettingsActions
}
