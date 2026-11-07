"""Create the update descriptor consumed by GKD from a built APK."""

import json
from pathlib import Path
import re
import sys
from urllib.parse import quote


if len(sys.argv) != 7:
    raise SystemExit(
        "Usage: create-update-manifest.py METADATA APK REPOSITORY TAG COMMIT OUTPUT",
    )
metadata_path, apk_path, repository, tag, commit_id, output_path = sys.argv[1:7]
metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
elements = metadata.get("elements", [])
if len(elements) != 1:
    raise SystemExit("Expected exactly one APK metadata element")
element = elements[0]
version_code = element.get("versionCode")
version_name = element.get("versionName")
if not isinstance(version_code, int) or not 1 <= version_code <= 2_100_000_000:
    raise SystemExit("Invalid APK versionCode")
if not isinstance(version_name, str) or not version_name:
    raise SystemExit("Invalid APK versionName")
if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
    raise SystemExit("Invalid GitHub repository")
if not tag or re.fullmatch(r"[0-9a-fA-F]{40}", commit_id) is None:
    raise SystemExit("Invalid release tag or commit ID")

apk = Path(apk_path)
if not apk.is_file() or apk.stat().st_size <= 0:
    raise SystemExit("APK does not exist or is empty")
asset_url = (
    f"https://github.com/{repository}/releases/download/"
    f"{quote(tag, safe='')}/{quote(apk.name, safe='')}"
)
short_commit = commit_id[:7]
manifest = {
    "versionCode": version_code,
    "versionName": version_name,
    "downloadUrl": asset_url,
    "fileSize": apk.stat().st_size,
    "versionLogs": [
        {
            "name": version_name,
            "code": version_code,
            "desc": f"Fork build {short_commit} from {commit_id}.",
        }
    ],
}
destination = Path(output_path)
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
