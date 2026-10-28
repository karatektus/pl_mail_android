"""Put one release of the google build on a Google Play track, with its notes and the store listing.

Usage: play-upload.py <version> <track> <bundle.aab> <mapping.txt>

The bundle is uploaded only when Play does not already have its versionCode. Play takes a
versionCode once, so a release that was uploaded before -- by hand, or to another track -- is
reused as it is. Reads the service account's JSON key from PLAY_SERVICE_ACCOUNT_JSON.

Texts come from fastlane/metadata/android/<locale>/, the layout F-Droid reads too: title.txt,
short_description.txt and full_description.txt for the listing, and changelogs/<versionCode>.txt
for the release notes. The en-US notes are required, so no release goes out without any.
"""

import json
import os
import sys
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "de.pluetzner.plmail"
METADATA = Path("fastlane/metadata/android")


def version_code_of(version):
    # The same derivation as versionCodeOf() in AndroidApplicationConventionPlugin.kt.
    major, minor, patch = (int(part) for part in version.split("-")[0].split("."))
    return major * 10_000 + minor * 100 + patch


def text(path):
    return path.read_text(encoding="utf-8").strip()


def main(version, track, bundle, mapping):
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    edits = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False).edits()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    version_code = version_code_of(version)

    locales = sorted(path for path in METADATA.iterdir() if path.is_dir())
    notes = [
        {"language": locale.name, "text": text(locale / "changelogs" / f"{version_code}.txt")}
        for locale in locales
        if (locale / "changelogs" / f"{version_code}.txt").is_file()
    ]
    if not any(note["language"] == "en-US" for note in notes):
        sys.exit(f"No release notes: write {METADATA}/en-US/changelogs/{version_code}.txt.")

    known = {b["versionCode"] for b in edits.bundles().list(packageName=PACKAGE, editId=edit).execute().get("bundles", [])}
    if version_code in known:
        print(f"Play already has versionCode {version_code}; putting that bundle on {track}.")
    else:
        uploaded = edits.bundles().upload(
            packageName=PACKAGE,
            editId=edit,
            media_body=MediaFileUpload(bundle, mimetype="application/octet-stream", resumable=True),
        ).execute()
        if uploaded["versionCode"] != version_code:
            sys.exit(f"The bundle is versionCode {uploaded['versionCode']}, but {version} should be {version_code}.")
        print(f"Uploaded {bundle} as versionCode {version_code}.")

        edits.deobfuscationfiles().upload(
            packageName=PACKAGE,
            editId=edit,
            apkVersionCode=version_code,
            deobfuscationFileType="proguard",
            media_body=MediaFileUpload(mapping, mimetype="application/octet-stream", resumable=True),
        ).execute()
        print("Uploaded the mapping file.")

    edits.tracks().update(
        packageName=PACKAGE,
        editId=edit,
        track=track,
        body={
            "track": track,
            "releases": [
                {"name": version, "versionCodes": [str(version_code)], "status": "completed", "releaseNotes": notes}
            ],
        },
    ).execute()
    print(f"Release notes in {', '.join(note['language'] for note in notes)}.")

    for locale in locales:
        edits.listings().update(
            packageName=PACKAGE,
            editId=edit,
            language=locale.name,
            body={
                "language": locale.name,
                "title": text(locale / "title.txt"),
                "shortDescription": text(locale / "short_description.txt"),
                "fullDescription": text(locale / "full_description.txt"),
            },
        ).execute()
    print(f"Store listing in {', '.join(locale.name for locale in locales)}.")
    edits.commit(packageName=PACKAGE, editId=edit).execute()
    print(f"{version} is on the {track} track.")


if __name__ == "__main__":
    main(*sys.argv[1:])
