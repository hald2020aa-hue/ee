#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
# Upgrade the already existing ee-downloader Git checkout without touching its .git history.
SOURCE="$(cd "$(dirname "$0")" && pwd)"
TARGET="$HOME/ee-downloader"
REPO="https://github.com/hald2020aa-hue/ee.git"
command -v git >/dev/null || { echo "Install: pkg install git"; exit 1; }
if [[ ! -d "$TARGET/.git" ]]; then
    if command -v gh >/dev/null; then gh auth status || { echo 'Run: gh auth login'; exit 1; }; fi
    git clone "$REPO" "$TARGET"
fi
if command -v gh >/dev/null; then
    gh auth status || { echo 'Run: gh auth login'; exit 1; }
    gh auth setup-git
fi
if [[ -n "$(git -C "$TARGET" status --porcelain)" ]]; then
    echo "The existing $TARGET directory has uncommitted changes. Stop to avoid overwriting them."
    exit 1
fi
# Copy only the project files (not an old or missing .git folder).
(cd "$SOURCE" && tar --exclude=.git --exclude=build --exclude=.gradle --exclude=app/build -cf - .) | (cd "$TARGET" && tar -xf -)
git -C "$TARGET" add -A
if git -C "$TARGET" diff --cached --quiet; then
    echo "No new changes to upload."
    exit 0
fi
if [[ -z "$(git -C "$TARGET" config user.name || true)" ]] || [[ -z "$(git -C "$TARGET" config user.email || true)" ]]; then
    echo "Set your Git name and email before committing."
    exit 1
fi
git -C "$TARGET" commit -m "feat: EE Tube v0.2 home UI, bookmarks and HLS VOD"
git -C "$TARGET" push origin main
echo "Uploaded. Check: https://github.com/hald2020aa-hue/ee/actions"
