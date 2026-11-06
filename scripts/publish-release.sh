#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

dry_run=false
if [[ "${1:-}" == --dry-run && $# == 1 ]]; then
    dry_run=true
elif [[ $# != 0 ]]; then
    echo "Usage: bash scripts/publish-release.sh [--dry-run]" >&2
    exit 1
fi

command -v python3 >/dev/null || { echo 'Install Python 3 first.' >&2; exit 1; }
if [[ -n "$(git status --porcelain)" ]]; then
    echo 'Commit changes before releasing; the APK must match the release tag.' >&2
    exit 1
fi
head=$(git rev-parse HEAD)

if [[ "$dry_run" == false ]]; then
    command -v gh >/dev/null || { echo 'Install GitHub CLI and run gh auth login.' >&2; exit 1; }
    gh auth status
fi

bash scripts/build-release.sh
if [[ -n "$(git status --porcelain)" || "$(git rev-parse HEAD)" != "$head" ]]; then
    echo 'Source changed during the build. Commit changes and build again.' >&2
    exit 1
fi
version=$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/release/output-metadata.json"))["elements"][0]["versionName"])')
tag="v$version"
assets="artifacts/release/$version"
notes="docs/releases/$version.md"
[[ -s "$notes" ]] || { echo "Add release notes at $notes." >&2; exit 1; }
if [[ "$dry_run" == true ]]; then
    echo "Verified $tag. Assets: $assets. No tag or GitHub release was created."
    exit 0
fi

# Publish only committed source that is already on the repository's default branch.
repository=belowevolve/a-ir-remote
branch=$(gh repo view "$repository" --json defaultBranchRef --jq '.defaultBranchRef.name')
git fetch origin "$branch"
git merge-base --is-ancestor "$head" FETCH_HEAD || {
    echo "Push this commit to origin/$branch before publishing." >&2; exit 1;
}
if gh release view "$tag" --repo "$repository" >/dev/null 2>&1; then
    echo "Release $tag already exists. Increment versionName and versionCode." >&2
    exit 1
fi
if git show-ref --verify --quiet "refs/tags/$tag"; then
    [[ "$(git rev-list -n 1 "$tag")" == "$head" ]] || { echo "$tag points to another commit." >&2; exit 1; }
else
    git tag -a "$tag" -m "Air Remote $version" "$head"
fi
git push origin "refs/tags/$tag"
# Keep incomplete uploads out of Obtainium's release feed.
gh release create "$tag" "$assets/Air-Remote-$version.apk" "$assets/SHA256SUMS.txt" \
    --repo "$repository" --verify-tag --draft --title "Air Remote $version" --notes-file "$notes"
gh release edit "$tag" --repo "$repository" --draft=false --latest
gh release view "$tag" --repo "$repository" --json url --jq .url
