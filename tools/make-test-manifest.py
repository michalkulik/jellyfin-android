"""Builds a latest.json next to a fake APK so the in-app updater can be tested locally.

Usage: python make-test-manifest.py <directory> <version> <versionCode> <baseUrl> [variant]
"""

import hashlib
import json
import os
import sys

directory = sys.argv[1]
version = sys.argv[2]
version_code = int(sys.argv[3])
base = sys.argv[4].rstrip("/")
variant = sys.argv[5] if len(sys.argv) > 5 else "proprietary"

apk_name = f"jellyfin-android-v{version}-{variant}-release.apk"
with open(os.path.join(directory, apk_name), "rb") as handle:
    payload = handle.read()

manifest = {
    "version": version,
    "versionCode": version_code,
    "publishedAt": "2026-09-20T20:00:00Z",
    "notes": "Test release used to verify the in-app updater end to end.",
    "variants": {
        variant: {
            "url": f"{base}/{apk_name}",
            "size": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        }
    },
}

with open(os.path.join(directory, "latest.json"), "w") as handle:
    json.dump(manifest, handle, indent=2)

print(json.dumps(manifest, indent=2))
