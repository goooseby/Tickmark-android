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
command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), ":app:assembleRelease", ":engine:test", "--console=plain"]
if args.version:
    import re
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        raise SystemExit("Invalid version")
    command.append(f"-PtickmarkVersionName={args.version}")
if args.version_code:
    command.append(f"-PtickmarkVersionCode={args.version_code}")
subprocess.run(command, cwd=root, env=env, check=True)
