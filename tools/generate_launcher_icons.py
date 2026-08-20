#!/usr/bin/env python3
"""Writes the per-colourway launcher icons, their aliases and the LogoStyle enum.

Run from the repository root:

    python3 tools/generate_launcher_icons.py

The user picks one of thirty-two logo colourways on the web (`LogoStyle` on the
server, default `berry`) and the phone's launcher icon follows it.  Thirty-two
colourways means thirty-two foreground vectors, thirty-two adaptive icons and
thirty-two `<activity-alias>` blocks, plus a Kotlin enum naming all of them --
about a hundred and thirty files and stanzas that have to agree with each other
character for character.  Hand-written, the first one to disagree would be a
launcher icon that either does not exist or cannot be switched to, and neither
failure is visible from any screen in the app.

So they come from one script and one table, and the table is `logostyles.json`
next to this file -- extracted by *running* the server's own enum rather than
transcribed off a page, which is why it is committed here as data rather than
retyped into Python.

## What is generated, and what is deliberately not

- `app/src/main/res/drawable/ic_launcher_<wire>_foreground.xml` for every
  colourway except the default.
- `app/src/main/res/mipmap-anydpi/ic_launcher_<wire>.xml`, likewise.
- `app/src/main/kotlin/de/plmail/LogoStyle.kt`, every colourway with the alias
  class name that carries it.
- The `<activity-alias>` block in `app/src/main/AndroidManifest.xml`, between
  the two BEGIN/END marker comments.

**The default colourway, `berry`, is generated only into the enum and the
manifest.**  Its ink is already on disk twice over -- `ic_launcher_foreground`
is berry, because berry is what the application's own `android:icon` has to be
for a launcher that has never been told otherwise -- so a generated
`ic_launcher_berry_foreground` would be a second copy of the fallback to keep in
step with the first.  Berry's alias points at `@mipmap/ic_launcher` instead, and
that reuse is what makes "the default keeps working" true by construction rather
than by two files happening to hold the same seven hexes.

**The geometry is never written by this script.**  The seven strokes are read
out of `ic_launcher_foreground.xml` and only their `strokeColor` attributes are
replaced, in draw order.  That file's docblock explains the safe-zone arithmetic
behind the scale and the translate; re-emitting the paths from a template here
would be a second transcription of the server's logo macro, and the whole reason
that file exists is that the first one drifted.  A correction to the mark
therefore reaches all thirty-two colourways by rerunning this.

**The `light` colours, never the `dark` ones.**  See the header this writes into
every generated vector: the adaptive background is a fixed off-white.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TABLE = Path(__file__).resolve().parent / "logostyles.json"

APP = ROOT / "app" / "src" / "main"
DRAWABLE = APP / "res" / "drawable"
MIPMAP = APP / "res" / "mipmap-anydpi"
MANIFEST = APP / "AndroidManifest.xml"
ENUM = APP / "kotlin" / "de" / "plmail" / "LogoStyle.kt"

TEMPLATE = DRAWABLE / "ic_launcher_foreground.xml"

# The product default, and the one colourway with no generated resources of its
# own: it is what `ic_launcher_foreground` and `@mipmap/ic_launcher` already
# hold. See the module docstring.
DEFAULT = "berry"

# How many strokes the mark is made of. Asserted against both the table and the
# template, because a mark redrawn with six or eight would otherwise be painted
# with whatever `zip` had left over -- silently, and in twenty-nine files.
STROKES = 7

ALIAS_PREFIX = "de.plmail.LogoLauncher"

BEGIN = "<!-- BEGIN generated launcher aliases: tools/generate_launcher_icons.py -->"
END = "<!-- END generated launcher aliases -->"

# A dashed wire name is not a resource name: aapt takes lowercase letters,
# digits and underscores, and would refuse `ic_launcher_product-blue` outright.
def resource(wire: str) -> str:
    return wire.replace("-", "_")


# `product-blue` -> `de.plmail.LogoLauncherProductBlue`. Spelled into the enum
# and into the manifest from this one function, so the string the app switches
# at runtime and the string the manifest declares cannot drift; the manifest
# suite asserts the pair against the merged manifest as well.
def alias(wire: str) -> str:
    return ALIAS_PREFIX + "".join(part.capitalize() for part in wire.split("-"))


# SCREAMING_SNAKE for the enum constant, which is the only spelling of a
# colourway that Kotlin's own conventions will accept.
def constant(wire: str) -> str:
    return wire.replace("-", "_").upper()


def styles() -> list[dict]:
    table = json.loads(TABLE.read_text(encoding="utf-8"))

    for style in table:
        light = style["light"]
        if len(light) != STROKES:
            raise SystemExit(
                f"{style['wire']} has {len(light)} colours, expected {STROKES}: "
                "the table and the mark disagree about how many strokes there are"
            )

    if not any(style["wire"] == DEFAULT for style in table):
        raise SystemExit(f"{DEFAULT} is not in {TABLE.name}; it is the product default")

    return table


VECTOR_HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by tools/generate_launcher_icons.py from tools/logostyles.json.
  Edit neither this file nor its thirty-odd siblings: rerun the script.

  plMail's "pl" mark in LogoStyle::{wire}, one of the thirty-two colourways the
  user chooses from on the web. The geometry is `ic_launcher_foreground`'s,
  copied stroke for stroke rather than re-transcribed from the server's logo
  macro — see that file's docblock for the safe-zone arithmetic behind the
  scale and the translate, and for why a hand-traced logo is a logo that
  drifts. Every colourway is the same seven strokes with different paint.

  These are the colourway's **light** strokes, and the dark ones are not
  generated anywhere. The adaptive background under this is
  `@color/ic_launcher_background`, a fixed off-white, in every theme the phone
  can be in — an app icon is a fixed asset and the launcher never redraws it
  for dark mode. So the dark variants would be painting for a background this
  icon never has, and on off-white several of them are close to invisible.
-->
"""


def write_vector(style: dict) -> None:
    template = TEMPLATE.read_text(encoding="utf-8")

    # The leading docblock goes; everything from `<vector` on is kept verbatim,
    # which is what makes this a repaint rather than a redraw.
    body = template[template.index("<vector") :]

    colours = iter(style["light"])
    body, replaced = re.subn(
        r'android:strokeColor="#[0-9a-fA-F]{6}"',
        lambda _: f'android:strokeColor="{next(colours)}"',
        body,
    )

    if replaced != STROKES:
        raise SystemExit(
            f"{TEMPLATE.name} has {replaced} stroke colours, expected {STROKES}: "
            "the mark was redrawn and this script has not been told"
        )

    header = VECTOR_HEADER.format(wire=style["wire"])
    path = DRAWABLE / f"ic_launcher_{resource(style['wire'])}_foreground.xml"
    path.write_text(header + body, encoding="utf-8")


MIPMAP_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by tools/generate_launcher_icons.py from tools/logostyles.json.
  Edit neither this file nor its thirty-odd siblings: rerun the script.

  The launcher icon for LogoStyle::{wire}, worn by the `{alias}`
  alias while that is the colourway the user has chosen on the web.

  The background is the same fixed off-white every colourway sits on, and the
  monochrome layer is the same silhouette they all share: the platform paints a
  themed icon in colours it derives from the wallpaper, so a per-colourway
  monochrome would be thirty-two files that render identically.

  There is no `_round` variant. `roundIcon` predates adaptive icons and means
  nothing to a launcher that masks this one itself; minSdk here is 31, so every
  launcher that can see this file masks it. The alias points both attributes at
  this drawable rather than leaving `roundIcon` to inherit the application's —
  which would put the *default* colourway back on a launcher that asks for the
  round one.
-->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_{resource}_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


def write_mipmap(style: dict) -> None:
    wire = style["wire"]
    path = MIPMAP / f"ic_launcher_{resource(wire)}.xml"
    path.write_text(
        MIPMAP_TEMPLATE.format(wire=wire, alias=alias(wire), resource=resource(wire)),
        encoding="utf-8",
    )


ALIAS_TEMPLATE = """        <activity-alias
            android:name="{alias}"
            android:enabled="{enabled}"
            android:exported="true"
            android:icon="{icon}"
            android:roundIcon="{icon}"
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>
"""


def write_manifest(table: list[dict]) -> None:
    blocks = []

    for style in table:
        wire = style["wire"]
        default = wire == DEFAULT

        blocks.append(
            ALIAS_TEMPLATE.format(
                alias=alias(wire),
                # Exactly one alias is enabled in the manifest, and it is the
                # product default. That is what a fresh install wears, what an
                # older server that sends no logoStyle leaves it wearing, and
                # what guarantees the rule that there is never an instant with
                # no launcher entry at all: the first one is on before the app
                # has run once.
                enabled="true" if default else "false",
                # See the module docstring for why the default reuses the
                # application's own icon rather than getting a copy of it.
                icon="@mipmap/ic_launcher" if default else f"@mipmap/ic_launcher_{resource(wire)}",
            )
        )

    text = MANIFEST.read_text(encoding="utf-8")
    start = text.index(BEGIN) + len(BEGIN)
    stop = text.index(END)

    MANIFEST.write_text(text[:start] + "\n" + "\n".join(blocks) + "\n" + text[stop:], "utf-8")


ENUM_HEADER = '''package de.plmail

/**
 * The thirty-two logo colourways, and the launcher alias that wears each one.
 *
 * GENERATED by `tools/generate_launcher_icons.py` from `tools/logostyles.json`, which was extracted
 * by running the server's own `LogoStyle` enum rather than transcribed off a page. Add a colourway
 * by adding it there and rerunning the script: the icons, the aliases in the manifest and this file
 * all come out of that one table, and the point of generating them together is that they cannot
 * disagree.
 *
 * [wire] is the value the JMAP `Appearance` object carries in its read-only `logoStyle` property.
 * [alias] is the `<activity-alias>` that carries that colourway's icon, spelled out in full rather
 * than derived from [wire] at runtime — `android:name=".LogoLauncherBerry"` resolves against the
 * module's **namespace**, `de.plmail`, and not against the applicationId, which carries a flavour
 * suffix (`.google`) and a build-type one (`.debug`). The package half of a `ComponentName` is the
 * applicationId and the class half is this; getting that round the wrong way yields a component the
 * system has never heard of, and `setComponentEnabledSetting` on one of those throws.
 * `LogoStyleManifestTest` asserts every one of these strings against the merged manifest.
 *
 * The colours themselves are deliberately **not** here. Nothing in this app paints the mark — the
 * launcher does, out of the drawable the alias names — so a copy of the hexes in Kotlin would be a
 * copy that no code reads and nothing checks.
 */
enum class LogoStyle(val wire: String, val alias: String) {
'''

ENUM_FOOTER = '''
    companion object {

        /**
         * The colourway the product ships with, and the answer to every question this build cannot
         * answer.
         *
         * It is the server's own default, the ink in `ic_launcher_foreground`, the application's
         * `android:icon`, and the one alias the manifest enables. Those four being the same thing
         * is what makes an unknown colourway degrade to a correct icon rather than to no icon.
         */
        val Default: LogoStyle = DEFAULT_STYLE

        /**
         * A wire name from the server, or [Default] for anything this build does not have.
         *
         * **Both absences are the same answer, and on purpose.** A server older than this feature
         * omits `logoStyle` entirely and arrives here as null; a server *newer* than this build can
         * send a colourway added after it shipped, and arrives here as a name no entry matches.
         * Neither is an error and neither may be a null: the value's only consumer switches a
         * launcher icon, so "I do not know this one" has to resolve to a real icon or the app is
         * one unrecognised string away from having none at all.
         */
        fun fromWire(wire: String?): LogoStyle = entries.firstOrNull { it.wire == wire } ?: Default
    }
}
'''


def write_enum(table: list[dict]) -> None:
    # The last entry is terminated with a semicolon rather than a comma, and
    # every line below is wrapped exactly as ktfmt wraps it. That is not
    # cosmetic here: `./gradlew build` runs Spotless as a *check*, so a
    # generator whose output ktfmt would reformat is a generator that leaves the
    # build red until somebody notices they have to run spotlessApply after it.
    # Regenerating has to be one command.
    entries = "".join(
        '    {}("{}", "{}"){}\n'.format(
            constant(style["wire"]),
            style["wire"],
            alias(style["wire"]),
            ";" if style is table[-1] else ",",
        )
        for style in table
    )

    ENUM.write_text(
        ENUM_HEADER + entries + ENUM_FOOTER.replace("DEFAULT_STYLE", constant(DEFAULT)),
        encoding="utf-8",
    )


def main() -> None:
    table = styles()

    for style in table:
        if style["wire"] == DEFAULT:
            continue

        write_vector(style)
        write_mipmap(style)

    write_manifest(table)
    write_enum(table)

    print(f"{len(table)} colourways: {2 * (len(table) - 1)} resources, {len(table)} aliases")


if __name__ == "__main__":
    main()
