package org.arkikeskus.launcher.data.di

import android.content.Context
import androidx.room.Room
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.arkikeskus.launcher.data.AppIconFetcher
import org.arkikeskus.launcher.data.AppIconKeyer
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.LauncherDatabase
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

    @Provides
    @Singleton
    fun provideLauncherDatabase(@ApplicationContext context: Context): LauncherDatabase =
        Room.databaseBuilder(context, LauncherDatabase::class.java, "launcher.db").build()

    @Provides
    fun provideHomeItemDao(database: LauncherDatabase): HomeItemDao = database.homeItemDao()
}
