"""Validate workflow inputs and restore a signing key from repository Secrets."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess

version = os.environ["VERSION"]
code = os.environ["VERSION_CODE"]
if not re.fullmatch(r"\d+\.\d+\.\d+", version) or not code.isdecimal() or not 0 < int(code) < 2_100_000_000:
    raise SystemExit("Invalid version or Android versionCode")
for key in ("ANDROID_KEYSTORE_BASE64", "TICKMARK_STORE_PASSWORD", "TICKMARK_KEY_PASSWORD", "TICKMARK_KEY_ALIAS"):
    if not os.environ.get(key):
        raise SystemExit(f"Missing signing secret: {key}")
existing = subprocess.run(["gh", "release", "view", f"v{version}"], capture_output=True)
if existing.returncode == 0:
    raise SystemExit("This release already exists; select a new version")
# Compare against the manifest attached to the latest published release, when present.
previous = subprocess.run(["gh", "api", f"repos/{os.environ['GH_REPO']}/releases/latest"], capture_output=True, text=True)
if previous.returncode == 0:
    release = json.loads(previous.stdout)
    if any(asset["name"] == "update.json" for asset in release.get("assets", [])):
        import urllib.request
        url = f"https://github.com/{os.environ['GH_REPO']}/releases/download/{release['tag_name']}/update.json"
        with urllib.request.urlopen(url, timeout=20) as response:
            last = json.load(response)
        if int(code) <= last["versionCode"]:
            raise SystemExit("versionCode must exceed the latest published release")
keyfile = Path(os.environ["TICKMARK_KEYSTORE_PATH"])
keyfile.write_bytes(base64.b64decode(os.environ["ANDROID_KEYSTORE_BASE64"], validate=True))
keyfile.chmod(0o600)
