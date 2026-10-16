"""Prepare a small release bundle; never include the keystore, raw SDK, or build logs."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("build/distribution"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    version = re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', (root / "app/build.gradle.kts").read_text())[1]
    destination = args.directory
    destination.mkdir(parents=True, exist_ok=True)
    allowed = {f"Izuna-{version}.apk", "DEPENDENCIES.txt", "SIGNING-CERT-SHA256.txt", "PRIVACY.md", "RELEASE_NOTES.md",
               "apk-audit.json", "source-dependency-audit.json", "SHA256SUMS.txt"}
    if any(path.name not in allowed or not path.is_file() for path in destination.iterdir()):
        raise ValueError("Unexpected file in distribution directory; do not upload")
    shutil.copyfile(root / "app/build/outputs/apk/release/app-release.apk", destination / f"Izuna-{version}.apk")
    shutil.copyfile(root / "app/build/reports/release-dependencies.txt", destination / "DEPENDENCIES.txt")
    shutil.copyfile(root / ".github/signing-cert-sha256.txt", destination / "SIGNING-CERT-SHA256.txt")
    shutil.copyfile(root / "docs/PRIVACY.md", destination / "PRIVACY.md")
    shutil.copyfile(root / "docs/releases" / f"v{version}.md", destination / "RELEASE_NOTES.md")
    for report in ("apk-audit.json", "source-dependency-audit.json"):
        source = root / "build/release-check" / report
        data = json.loads(source.read_text())
        if data.get("passed") is not True:
            raise ValueError("Release audit did not pass")
        shutil.copyfile(source, destination / report)
    files = sorted(path for path in destination.iterdir() if path.is_file() and path.name != "SHA256SUMS.txt")
    destination.joinpath("SHA256SUMS.txt").write_text("".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files))
    print(f"Prepared Izuna {version} release artifacts")


if __name__ == "__main__":
    main()
