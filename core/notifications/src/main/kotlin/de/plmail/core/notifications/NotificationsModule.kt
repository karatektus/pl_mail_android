package de.plmail.core.notifications

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.plmail.core.data.NewMailListener
import javax.inject.Singleton

/**
 * Attaches the notifier to the sync.
 *
 * `@IntoSet` rather than a direct binding, so that removing this module from a build leaves
 * `:core:data` with an empty set and a working sync rather than an unsatisfied dependency. Which is
 * the point of the seam: notifying is something done *about* mail arriving, not part of it
 * arriving.
 *
 * `MailDestinations` is deliberately **not** bound here. Only `:app` knows which activity a tap
 * should reach, and a default implementation in this module would be a guess that compiles.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationsModule {

    @Binds @IntoSet @Singleton abstract fun mailNotifier(real: MailNotifier): NewMailListener
}
