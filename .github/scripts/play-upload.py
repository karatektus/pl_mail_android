"""Put one release of the google build on a Google Play track.

Usage: play-upload.py <version> <track> <bundle.aab> <mapping.txt>

The bundle is uploaded only when Play does not already have its versionCode. Play takes a
versionCode once, so a release that was uploaded before -- by hand, or to another track -- is
reused as it is. Reads the service account's JSON key from PLAY_SERVICE_ACCOUNT_JSON.
"""

import json
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "de.pluetzner.plmail"


def version_code_of(version):
    # The same derivation as versionCodeOf() in AndroidApplicationConventionPlugin.kt.
    major, minor, patch = (int(part) for part in version.split("-")[0].split("."))
    return major * 10_000 + minor * 100 + patch


def main(version, track, bundle, mapping):
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    edits = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False).edits()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    version_code = version_code_of(version)

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
        body={"track": track, "releases": [{"name": version, "versionCodes": [str(version_code)], "status": "completed"}]},
    ).execute()
    edits.commit(packageName=PACKAGE, editId=edit).execute()
    print(f"{version} is on the {track} track.")


if __name__ == "__main__":
    main(*sys.argv[1:])
