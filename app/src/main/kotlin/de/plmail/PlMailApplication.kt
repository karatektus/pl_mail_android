package de.plmail

import android.app.Application

/**
 * The application object.
 *
 * Deliberately near-empty, and meant to stay that way. Everything the app needs at launch belongs
 * behind an injected dependency that can be replaced in a test; work done here runs on every cold
 * start, before anything knows whether it is needed, and cannot be swapped out.
 */
class PlMailApplication : Application()
