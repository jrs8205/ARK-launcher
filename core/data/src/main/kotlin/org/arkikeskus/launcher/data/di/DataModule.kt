package org.arkikeskus.launcher.data.di

import android.content.Context
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.arkikeskus.launcher.data.AppIconFetcher
import org.arkikeskus.launcher.data.AppIconKeyer
import org.arkikeskus.launcher.data.LauncherAppsSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        source: LauncherAppsSource,
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(AppIconKeyer())
            add(AppIconFetcher.Factory(source))
        }
        .build()
}
