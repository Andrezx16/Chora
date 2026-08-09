package com.craftworks.music.data.di

import android.content.Context
import com.craftworks.music.data.datasource.LyricsDataSource
import com.craftworks.music.data.datasource.betterlyrics.BetterLyricsDataSource
import com.craftworks.music.data.datasource.lrclib.LrclibDataSource
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import com.craftworks.music.data.datasource.navidrome.NavidromeLyricsDataSource
import com.craftworks.music.data.datasource.netease.NeteaseDataSource
import com.craftworks.music.data.datasource.local.LocalDataSource
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.managers.settings.LocalDataSettingsManager
import com.craftworks.music.managers.settings.MediaProviderSettingsManager
import com.craftworks.music.providers.local.LocalProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {

    @Singleton
    @Provides
    fun provideLocalDataSource(
        localProvider: LocalProvider,
        localDataSettingsManager: LocalDataSettingsManager,
        appearanceSettingsManager: AppearanceSettingsManager
    ): LocalDataSource {
        return LocalDataSource(localProvider, localDataSettingsManager, appearanceSettingsManager)
    }

    @Singleton
    @Provides
    fun provideNavidromeDataSource(): NavidromeDataSource {
        return NavidromeDataSource()
    }

    @Singleton
    @Provides
    fun provideBetterLyricsDataSource(): BetterLyricsDataSource {
        return BetterLyricsDataSource()
    }

    @Singleton
    @Provides
    fun provideLrcLibDataSource(
        settingsManager: MediaProviderSettingsManager,
        @ApplicationContext context: Context
    ): LrclibDataSource {
        return LrclibDataSource(settingsManager, context)
    }

    @Singleton
    @Provides
    fun provideNetEaseDataSource(
        settingsManager: MediaProviderSettingsManager,
        @ApplicationContext context: Context
    ): NeteaseDataSource {
        return NeteaseDataSource(settingsManager, context)
    }

    @Singleton
    @Provides
    fun provideNavidromeLyricsDataSource(
        navidromeDataSource: NavidromeDataSource
    ): NavidromeLyricsDataSource {
        return NavidromeLyricsDataSource(navidromeDataSource)
    }

    @Singleton
    @Provides
    fun provideLyricsDataSources(
        betterLyrics: BetterLyricsDataSource,
        lrcLib: LrclibDataSource,
        navidromeLyrics: NavidromeLyricsDataSource,
        netease: NeteaseDataSource
    ): List<LyricsDataSource> {
        return listOf(betterLyrics, lrcLib, navidromeLyrics, netease)
    }
}
