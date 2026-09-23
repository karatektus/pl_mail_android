package de.plmail

/**
 * One launcher icon this build can wear: a motif, a paint, and the alias that carries the pair.
 *
 * The user picks the icon in the browser, in two steps: the **motif** — the pl mark, a horn, a love
 * letter — and then the **paint**, which is that motif's `original` design or one of the thirty-two
 * logo colourways applied by the motif's own recipe. The JMAP `Appearance` singleton publishes the
 * pair as read-only `logoMotif` and `logoPaint`, beside the older `logoStyle`, which still names
 * the pl mark's colourway and is all a server older than this feature sends.
 *
 * Every pair is an `<activity-alias>` compiled into the APK, because switching aliases is the only
 * way an installed app's icon can change — see [AppLauncherIcon]. [all] is the table of them, and
 * it is **generated at build time** by build-logic's `LauncherIconsConventionPlugin`, from
 * `tools/logo-paints.json`, in the same action that writes the aliases and the drawables they wear.
 * That is the whole reason it is generated rather than written here: the list the app switches by
 * and the list the manifest declares cannot disagree when one action writes both.
 *
 * [alias] is spelled out in full rather than derived at runtime. `android:name` in the manifest
 * resolves against the module's **namespace**, `de.plmail`, and not against the applicationId,
 * which carries a flavour suffix (`.google`) and a build-type one (`.debug`). The package half of a
 * `ComponentName` is the applicationId and the class half is this; getting that round the wrong way
 * yields a component the system has never heard of, and `setComponentEnabledSetting` on one of
 * those throws. `LauncherIconManifestTest` asserts every one of these strings against the merged
 * manifest.
 *
 * The pl mark's aliases keep the names they had when the colourway was the only choice —
 * `LogoLauncherOcean` — because an installed phone has one of them enabled and the enabled state is
 * stored against the name: renaming them would take the icon off somebody's home screen in an
 * update. Every other motif's alias spells both halves, `LogoLauncherBlueHornOcean`.
 *
 * The colours are deliberately **not** here. Nothing in this app paints an icon — the launcher
 * does, out of the drawable the alias names — so a copy of the hexes in Kotlin would be a copy that
 * no code reads and nothing checks.
 */
data class LauncherIcon(val motif: String, val paint: String, val alias: String) {

    companion object {

        /** The pl mark: the product's own logo, and the only motif `logoStyle` ever described. */
        const val PL = "pl"

        /** A motif's own design, as drawn: the paint every motif but the pl mark starts in. */
        const val ORIGINAL = "original"

        /** Every icon in this build, one per alias, in the order the manifest declares them. */
        val all: List<LauncherIcon> = LAUNCHER_ICONS

        /**
         * The icon the product ships with, and the answer to every question this build cannot
         * answer.
         *
         * The pl mark in berry: the server's own default, the ink in `ic_launcher_foreground`, the
         * application's `android:icon`, and the one alias the manifest enables. Those four being
         * the same thing is what makes anything unknown degrade to a correct icon rather than to no
         * icon.
         */
        val Default: LauncherIcon = DEFAULT_LAUNCHER_ICON

        private val byPair: Map<Pair<String, String>, LauncherIcon> = all.associateBy {
            it.motif to it.paint
        }

        private val motifs: Set<String> = all.mapTo(HashSet()) { it.motif }

        /**
         * What the server said, as one of [all] — never null, and never an icon this build lacks.
         *
         * **The motif first**: [logoMotif], or the pl mark when there is none. A server older than
         * this feature sends no motif at all, and the pl mark is the only icon it ever described. A
         * motif this build has never heard of — the server grew one after this APK shipped — is the
         * pl mark too, with the paint still applied where the mark has it: somebody who chose an
         * icon in ocean gets the logo in ocean, which is the nearest thing this build can draw.
         *
         * **Then the paint, and the pl mark is the one motif that reads it differently.** Its
         * colourway has been published as [logoStyle] since before there were motifs, so that is
         * the fallback when [logoPaint] is absent — which is exactly what an older server looks
         * like, and why a phone updated against one keeps the colourway it was already wearing.
         * Unknown lands on [Default], as it always has. So does `original`: the pl mark has no
         * design of its own apart from berry, and the build checks that the table agrees.
         *
         * Every other motif starts in [ORIGINAL], and a paint this build does not have for it — a
         * colourway added after it shipped — is that motif's original rather than the default. The
         * user chose the horn; the horn in its own colours is closer to that than the logo.
         *
         * Nothing here may return null. The only consumer switches a launcher icon, so "I do not
         * know that one" has to end at a real alias, or the app is one unrecognised string away
         * from having none at all.
         */
        fun resolve(logoMotif: String?, logoPaint: String?, logoStyle: String?): LauncherIcon {
            val motif = logoMotif?.takeIf { it in motifs } ?: PL

            if (motif == PL) {
                return (logoPaint ?: logoStyle)?.let { byPair[PL to it] } ?: Default
            }

            return byPair[motif to (logoPaint ?: ORIGINAL)] ?: byPair[motif to ORIGINAL] ?: Default
        }
    }
}
