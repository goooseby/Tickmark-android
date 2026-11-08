"""Build with a local signing configuration; secrets are passed only through the environment."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--signing-config", required=True, type=Path)
parser.add_argument("--version")
parser.add_argument("--version-code", type=int)
args = parser.parse_args()
settings = json.loads(args.signing_config.read_text(encoding="utf-8"))
env = os.environ.copy()
env.update(TICKMARK_KEYSTORE_PATH=settings["keystore"], TICKMARK_STORE_PASSWORD=settings["storePassword"],
           TICKMARK_KEY_PASSWORD=settings["keyPassword"], TICKMARK_KEY_ALIAS=settings["alias"])
root = Path(__file__).resolve().parent.parent
command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), ":app:assembleRelease", ":app:lintRelease", ":engine:test", "--console=plain"]
if args.version:
    import re
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        raise SystemExit("Invalid version")
    command.append(f"-PtickmarkVersionName={args.version}")
if args.version_code:
    command.append(f"-PtickmarkVersionCode={args.version_code}")
subprocess.run(command, cwd=root, env=env, check=True)

# Keep the exact mapping alongside a content-addressed APK identity. A subsequent
# build otherwise overwrites outputs/mapping/release, including same-version QA.
import hashlib
import shutil
apk = root / "app/build/outputs/apk/release/app-release.apk"
mapping = root / "app/build/outputs/mapping/release"
if not (mapping / "mapping.txt").is_file():
    raise SystemExit("Release R8 mapping is missing")
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
archive = root / "build/release-symbols" / digest
shutil.copytree(mapping, archive, dirs_exist_ok=True)
shutil.copy2(root / "app/build/outputs/apk/release/output-metadata.json", archive / "output-metadata.json")
(archive / "SHA256SUMS.txt").write_text(f"{digest}  app-release.apk\n", encoding="utf-8")
print(f"R8 mapping archived: {archive}")
