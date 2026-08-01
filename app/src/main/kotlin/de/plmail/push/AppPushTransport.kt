package de.plmail.push

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.PushTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UnifiedPush, as the rest of the app is allowed to see it.
 *
 * Bound here rather than in `:core:data` for the same reason `MailDestinations` is: only `:app`
 * depends on the connector, and only `:app` knows whether this build has one at all. A `google`
 * flavour that grew an FCM sender would replace this class and nothing above it would notice.
 */
@Singleton
class AppPushTransport @Inject constructor(@param:ApplicationContext private val context: Context) :
    PushTransport {

    override fun distributor(): String? = PushSetup.distributor(context)

    override fun installed(): List<String> = PushSetup.available(context)

    override fun register(): Boolean = PushSetup.enable(context)

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class Bindings {
        @Binds @Singleton abstract fun transport(real: AppPushTransport): PushTransport
    }
}
