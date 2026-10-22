package de.plmail.feature.calendar

/**
 * Broadcast when a calendar sync rewrote the local cache.
 *
 * The home-screen widgets live in `:app` and this module must not depend on it, so a refresh that
 * changed what the widgets draw announces itself this way instead of calling them. `:app`'s
 * `CalendarSyncedReceiver` turns it into a Glance update. The action string is mirrored in `:app`'s
 * `AndroidManifest.xml` — keep the two identical.
 */
const val ACTION_CALENDAR_SYNCED = "de.plmail.feature.calendar.action.CALENDAR_SYNCED"
