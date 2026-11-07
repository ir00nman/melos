#!/usr/bin/env bash
# Melos для macOS: Melos.app и Melos-<версия>.dmg (jpackage) → dist/.
# Собираем вне проекта: Рабочий стол в iCloud вешает на файлы атрибуты, из-за которых падает подпись (codesign).
# Проверку производителя JDK отключаем: у Homebrew-JDK есть jpackage и jmods, сборка с ним проходит.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${WORK:-$HOME/Library/Caches/melos-build/mac}"
: "${JAVA_HOME:?Укажите JAVA_HOME — JDK 21}"
"$ROOT/gradlew" -p "$ROOT" -q :app:packageDmg -PdevOut="$WORK" -Pcompose.desktop.packaging.checkJdkVendor=false
mkdir -p "$ROOT/dist"
cp "$WORK"/compose/binaries/main/dmg/*.dmg "$ROOT/dist/"
echo "готово: $(ls "$ROOT"/dist/*.dmg)"
