#!/usr/bin/env bash
#
# Installs a release APK on an already-running device and asserts it is still
# alive a moment later.
#
# **A file rather than an inline `script:` block**, and that is not tidiness.
# `reactivecircus/android-emulator-runner` runs the block it is given one line
# at a time, each in its own `sh -c`. So a variable assigned on one line is gone
# by the next, `set -eu` applies to a shell that has already exited, and an `if`
# is a syntax error because its `fi` arrives in a different process. Written
# inline, this job installed the app, resolved an empty package id, started
# `/de.plmail.MainActivity` -- no package at all -- and then died on the `if`.
# One line calling one script is the only shape that behaves the way anybody
# reading it expects.
#
# Usage: smoke.sh <apk>
set -euo pipefail

apk="${1:?usage: smoke.sh <apk>}"

adb install -r "$apk"

# Read out of the APK, not off the device.
#
# The flavours carry an applicationIdSuffix -- google is `de.plmail.google`,
# debug adds `.debug` -- so a hardcoded `de.plmail` starts nothing and reports a
# crash that did not happen. The obvious repair, grepping `pm list packages` for
# `de.plmail`, is worse: it matches every variant and `head -1` silently picks
# whichever the device lists first. Doing exactly that on a workstation
# "verified" a release by launching a leftover *debug* install and reporting it
# green. A runner is cleaner than a workstation, but a check that can pass by
# testing the wrong artifact is not a check.
#
# The APK knows its own id, and it is the artifact under test, so it is the only
# honest source.
aapt2="$(find "$ANDROID_HOME/build-tools" -name aapt2 | sort -V | tail -n 1)"
package="$("$aapt2" dump packagename "$apk" | tr -d '\r')"

if [ -z "$package" ]; then
    echo "::error::Could not read a package name out of $apk."
    exit 1
fi

echo "Starting $package"

adb logcat -c
adb shell am start -n "$package/de.plmail.MainActivity"
sleep 15

# `pidof` rather than scraping logcat for FATAL: a crash loop can restart faster
# than a scrape notices, and "is it running" is the actual claim. The logcat dump
# is for the human reading a red build, not for the assertion.
if adb shell pidof "$package" > /dev/null; then
    echo "The app is running."
else
    echo "::error::The release build crashed on launch."
    adb logcat -d -b crash
    exit 1
fi
