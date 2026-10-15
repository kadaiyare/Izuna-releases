"""Build a signed release without passing passwords as command-line arguments."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signing-file", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    env = os.environ.copy()
    if args.signing_file:
        config = json.loads(args.signing_file.read_text(encoding="utf-8"))
        env.update({"ANDROID_KEYSTORE_PATH": str(Path(config["keystore"]).resolve()),
                    "ANDROID_KEYSTORE_PASSWORD": config["store_password"], "ANDROID_KEY_ALIAS": config["alias"],
                    "ANDROID_KEY_PASSWORD": config["key_password"]})
    if not all(env.get(name) for name in ("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")):
        raise SystemExit("Release signing is not configured; an unsigned or debug-signed APK must not be distributed")
    wrapper = [str(root / "gradlew.bat")] if os.name == "nt" else ["bash", str(root / "gradlew")]
    return subprocess.call(wrapper + [":app:assembleRelease", ":app:lintRelease", ":app:testDebugUnitTest",
                                      ":app:releaseDependencyInventory", "--no-configuration-cache", "--console=plain"],
                           cwd=root, env=env)


if __name__ == "__main__":
    sys.exit(main())
