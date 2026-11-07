#!/usr/bin/env bash
# Релиз Melos одним тегом: ./publish.sh 1.0.1 [текст заметок]
# Собирает Windows-zip (кросс-сборка) и mac-DMG, публикует в GitHub Releases.
# Именно эти файлы ищет обновлялка в приложении (Updater): *windows-x64.zip и *.dmg.
# Нужны: JAVA_HOME (JDK 21), gh (GitHub CLI) с выполненным входом: gh auth login.
set -euo pipefail

VER="${1:?Версия: ./publish.sh 1.0.1}"
NOTES="${2:-Melos $VER}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist"
: "${JAVA_HOME:?Укажите JAVA_HOME — JDK 21}"
command -v gh >/dev/null || { echo "нет gh CLI: brew install gh && gh auth login"; exit 1; }
mkdir -p "$OUT"

echo "1/3 Windows-zip (кросс-сборка)"
APPVER="$VER" "$ROOT/packaging/windows/build.sh" >/dev/null

echo "2/3 DMG для macOS"
"$ROOT/gradlew" -p "$ROOT" -q :app:packageDmg -PappVersion="$VER"
DMG="$ROOT/app/build/compose/binaries/main/dmg/Melos-$VER.dmg"
[ -f "$DMG" ] || { echo "DMG не собрался: $DMG"; exit 1; }

echo "3/3 GitHub Releases: v$VER"
gh release create "v$VER" "$OUT/Melos-windows-x64.zip" "$DMG" --title "Melos $VER" --notes "$NOTES"
echo "готово: https://github.com/$(git -C "$ROOT" remote get-url origin | sed -E 's#.*github.com[:/]##; s#\.git$##')/releases/tag/v$VER"
