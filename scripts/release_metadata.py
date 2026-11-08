#!/usr/bin/env python3
"""Read release versions; optionally skip an already published GitHub release."""
import argparse
import json
import os
from pathlib import Path
import re
import tomllib
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-existing", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    build = (root / "app/build.gradle.kts").read_text()
    match = re.search(r'^\s*versionName = "(\d+\.\d+\.\d+)"\s*$', build, re.MULTILINE)
    if not match:
        raise ValueError("Expected a stable Android versionName (major.minor.patch)")
    version = match.group(1)
    companion = tomllib.loads((root / "companion/Cargo.toml").read_text())["package"]["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", companion):
        raise ValueError("Expected a stable companion version")
    tag = f"v{version}"
    publish = True
    if args.check_existing:
        repo = os.environ["GITHUB_REPOSITORY"]
        headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
        if token := os.environ.get("GH_TOKEN"):
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                publish = bool(json.load(response)["draft"])
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    for key, value in {"android": version, "companion": companion, "tag": tag,
                       "publish": str(publish).lower()}.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    main()
