#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

REPO="https://github.com/hald2020aa-hue/ee.git"
if ! command -v git >/dev/null; then
  echo "Install Git first: pkg install git"
  exit 1
fi
if command -v gh >/dev/null; then
  gh auth status || { echo "Sign in first with: gh auth login"; exit 1; }
  gh auth setup-git
fi
if [[ -z "$(git config user.name || true)" ]] || [[ -z "$(git config user.email || true)" ]]; then
  echo 'Configure Git identity first:'
  echo 'git config --global user.name "YOUR_GITHUB_NAME"'
  echo 'git config --global user.email "YOUR_EMAIL"'
  exit 1
fi
if [[ ! -d .git ]]; then
  git init -b main
fi
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REPO"
else
  git remote add origin "$REPO"
fi
git add .
if ! git diff --cached --quiet; then
  git commit -m "feat: initial EE Downloader Android MVP"
fi
git push -u origin main
echo "Uploaded. Open: https://github.com/hald2020aa-hue/ee/actions"
