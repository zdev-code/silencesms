#!/usr/bin/env bash

# Import a patch from TextSecure and adapt it for Silence
# ../Signal-Android must be an cloned git tree
# usage: ./scripts/import-patch.sh <commit SHAs>

set -eo pipefail

UPSTREAM="https://github.com/signalapp/Signal-Android"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

if [ "$#" -lt 1 ]; then
    echo "usage: ./scripts/import-patch.sh <commit SHAs>"
    exit 1
fi

cd "$REPO_ROOT"

for sha in "$@"; do
    wget "$UPSTREAM/commit/$sha.patch" 2> /dev/null
    "$SCRIPT_DIR/fix-patch.sh" "$REPO_ROOT/$sha.patch"
done

git checkout master > /dev/null 2>&1
