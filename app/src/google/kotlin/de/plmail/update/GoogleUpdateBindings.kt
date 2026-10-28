package de.plmail.update

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.AppUpdater
import de.plmail.core.data.NoAppUpdater
import javax.inject.Singleton

/**
 * The `google` flavour's updater: none.
 *
 * Play updates this build, and Play's policy forbids it updating itself — so it carries neither the
 * updater nor `REQUEST_INSTALL_PACKAGES`, which only the foss manifest declares. The version
 * section of the diagnostics screen reads [AppUpdater.isSupported] and shows the version alone.
 */
@Module
@InstallIn(SingletonComponent::class)
object GoogleUpdateBindings {

    @Provides @Singleton fun updater(): AppUpdater = NoAppUpdater
}
