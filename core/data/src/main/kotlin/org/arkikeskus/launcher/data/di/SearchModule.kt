package org.arkikeskus.launcher.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.arkikeskus.launcher.data.search.AndroidPermissionChecker
import org.arkikeskus.launcher.data.search.AppSearchProvider
import org.arkikeskus.launcher.data.search.CalculatorSearchProvider
import org.arkikeskus.launcher.data.search.ContactDataSource
import org.arkikeskus.launcher.data.search.ContactSearchProvider
import org.arkikeskus.launcher.data.search.ContentResolverContactDataSource
import org.arkikeskus.launcher.data.search.PermissionChecker
import org.arkikeskus.launcher.data.search.SearchProvider
import org.arkikeskus.launcher.data.search.SettingsSearchProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {
    @Binds @IntoSet abstract fun appProvider(p: AppSearchProvider): SearchProvider
    @Binds @IntoSet abstract fun settingsProvider(p: SettingsSearchProvider): SearchProvider
    @Binds @IntoSet abstract fun contactProvider(p: ContactSearchProvider): SearchProvider
    @Binds @IntoSet abstract fun calcProvider(p: CalculatorSearchProvider): SearchProvider

    @Binds abstract fun contactDataSource(impl: ContentResolverContactDataSource): ContactDataSource
    @Binds abstract fun permissionChecker(impl: AndroidPermissionChecker): PermissionChecker
}
