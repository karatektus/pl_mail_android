package de.plmail

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The application object.
 *
 * Deliberately near-empty, and meant to stay that way. Everything the app needs at launch belongs
 * behind an injected dependency that can be replaced in a test; work done here runs on every cold
 * start, before anything knows whether it is needed, and cannot be swapped out.
 *
 * [HiltAndroidApp] is the one exception, and it is not really work — it is the annotation that
 * generates the component everything else is injected from.
 */
@HiltAndroidApp class PlMailApplication : Application()
