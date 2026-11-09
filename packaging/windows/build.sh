#!/usr/bin/env bash
# Портативный Melos для Windows x64 — собирается на маке или в Linux (нативный установщик jpackage делает только на Windows):
#   jar с Windows-частями (Compose/Skia, JavaFX) + Java 21 для Windows (Temurin) + Melos.exe (Launch4j) → dist/Melos-windows-x64.zip,
#   и то же одним файлом (warp-packer) → dist/Melos.exe.
# Нужен JAVA_HOME с JDK 21. На маке Apple Silicon Launch4j работает через Rosetta. На Windows нужен VLC 3 (64-бит).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/packaging/windows"
WORK="$ROOT/build/windows"
CACHE="$WORK/cache"
OUT="${OUT:-$ROOT/dist}"
: "${JAVA_HOME:?Укажите JAVA_HOME — JDK 21}"
mkdir -p "$CACHE" "$OUT"

echo "1/4 jar под Windows"
# APPVER=1.0.1 — прошить версию в melos/version.txt (для обновлялки); публикует packaging/publish.sh
VER_ARG="${APPVER:+-PappVersion=$APPVER}"
"$ROOT/gradlew" -p "$ROOT" -q :app:packageUberJarForCurrentOS -Ptarget=windows -PdevOut="$WORK/gradle" $VER_ARG
JAR="$(ls -t "$WORK"/gradle/compose/jars/*.jar | head -1)"   # новейший: старые сборки лежат рядом

echo "2/4 Java 21 для Windows (Temurin)"
JRE_API="https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"
if [ ! -f "$CACHE/jre.zip" ]; then
  JRE_URL="$(curl -sI "$JRE_API" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')"
  curl -sfL -o "$CACHE/jre.zip.part" "$JRE_URL"
  curl -sfL -o "$CACHE/jre.zip.sha256.txt" "$JRE_URL.sha256.txt"
  echo "$(awk '{print $1}' "$CACHE/jre.zip.sha256.txt")  $CACHE/jre.zip.part" | shasum -a 256 -c - >/dev/null
  mv "$CACHE/jre.zip.part" "$CACHE/jre.zip"
fi

echo "3/4 Launch4j"
L4J="$CACHE/launch4j"
if [ ! -f "$L4J/launch4j.jar" ]; then
  case "$(uname)" in Darwin) PKG=macosx-x86 ;; *) PKG=linux-x64 ;; esac
  curl -sfL -o "$CACHE/l4j.tgz" "https://downloads.sourceforge.net/project/launch4j/launch4j-3/3.50/launch4j-3.50-$PKG.tgz"
  tar xzf "$CACHE/l4j.tgz" -C "$CACHE"
fi

echo "4/4 сборка папки и Melos.exe"
APP="$WORK/Melos"
rm -rf "$APP" && mkdir -p "$APP/app"
cp "$JAR" "$APP/app/melos.jar"
rm -rf "$WORK/jre-unpack" && mkdir "$WORK/jre-unpack" && unzip -q "$CACHE/jre.zip" -d "$WORK/jre-unpack"
mv "$WORK"/jre-unpack/*/ "$APP/jre"
# windres из Launch4j не понимает не-ASCII в путях («проекты») — exe собираем во временной папке с латинским путём
TMP_L4J="$(mktemp -d /tmp/melos-l4j.XXXXXX)"
cp "$HERE/melos.ico" "$TMP_L4J/melos.ico"
sed -e "s|@OUT@|$TMP_L4J/Melos.exe|" -e "s|@ICON@|$TMP_L4J/melos.ico|" "$HERE/launch4j.xml" > "$TMP_L4J/launch4j.xml"
"$JAVA_HOME/bin/java" -jar "$L4J/launch4j.jar" "$TMP_L4J/launch4j.xml"
mv "$TMP_L4J/Melos.exe" "$APP/Melos.exe"
rm -rf "$TMP_L4J"
cat > "$APP/ПРОЧТИ.txt" <<'TXT'
Melos для Windows — портативная версия: распакуйте папку куда угодно и запустите Melos.exe.
Java уже внутри (папка jre). Для звука нужен VLC 3, 64-битный: https://www.videolan.org/vlc/
Настройки и входы в сервисы лежат в %APPDATA%\Melos.
TXT
rm -f "$OUT/Melos-windows-x64.zip"
(cd "$WORK" && zip -qr -9 "$OUT/Melos-windows-x64.zip" Melos)
echo "готово: $OUT/Melos-windows-x64.zip ($(du -h "$OUT/Melos-windows-x64.zip" | cut -f1))"

# Один файл: warp-packer склеивает всю папку (exe, программа, Java) в Melos.exe; первый запуск распаковывает её
# в %LOCALAPPDATA%\warp, дальше запускается сразу. Загрузчик warp — консольный: правим подсистему PE на «окно» (2),
# иначе рядом с Melos висит чёрное окно консоли.
echo "один файл: Melos.exe"
WARP="$CACHE/warp-packer"
if [ ! -x "$WARP" ]; then
  case "$(uname)" in Darwin) WPKG=macos-x64 ;; *) WPKG=linux-x64 ;; esac
  curl -sfL -o "$WARP" "https://github.com/dgiagio/warp/releases/download/v0.3.0/$WPKG.warp-packer"
  chmod +x "$WARP"
fi
rm -f "$OUT/Melos.exe"
"$WARP" --arch windows-x64 --input_dir "$APP" --exec Melos.exe --output "$OUT/Melos.exe" >/dev/null
python3 - "$OUT/Melos.exe" <<'PY'
import struct, sys
p = sys.argv[1]
with open(p, 'r+b') as f:
    f.seek(0x3C); pe = struct.unpack('<I', f.read(4))[0]
    f.seek(pe); assert f.read(4) == b'PE\0\0', 'не PE-файл'
    f.seek(pe + 0x5C); sub = struct.unpack('<H', f.read(2))[0]
    assert sub in (2, 3), f'неожиданная подсистема {sub}'
    f.seek(pe + 0x5C); f.write(struct.pack('<H', 2))
PY
echo "готово: $OUT/Melos.exe ($(du -h "$OUT/Melos.exe" | cut -f1)) — один файл, Java внутри"
