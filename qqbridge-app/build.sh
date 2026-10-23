#!/bin/bash
# 独立 QQ 桥 App（com.dshstatus.qqbridge）构建脚本：复用 dsh-status-hud 的依赖。
set -e
cd "$(dirname "$0")"
DEPS=${DEPS:-../dsh-status-hud/android/build}
BUILD=build
SDK_JAR=${SDK_JAR:-$DEPS/android.jar}
D8_JAR=${D8_JAR:-$DEPS/d8.jar}
OUT=${OUT:-dist/QQ-Bridge.apk}
GEN=$BUILD/gen

[ -f "$SDK_JAR" ] || { echo "缺 $SDK_JAR（先构建/下载 dsh-status-hud 依赖）"; exit 1; }
[ -f "$D8_JAR" ] || { echo "缺 $D8_JAR"; exit 1; }

rm -rf "$BUILD/classes" "$BUILD/dex" "$GEN"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$GEN"

echo "== 1/6 javac =="
javac -source 8 -target 8 -Xlint:-options \
      -bootclasspath "$SDK_JAR" \
      -d "$BUILD/classes" \
      $(find src -name '*.java')

echo "== 2/6 d8 =="
java -cp "$D8_JAR" com.android.tools.r8.D8 --lib "$SDK_JAR" --min-api 29 \
     --output "$BUILD/dex" \
     $(find "$BUILD/classes" -name '*.class')

echo "== 3/6 aapt =="
aapt package -f -M AndroidManifest.xml -I "$SDK_JAR" -F "$BUILD/base.apk"
zip -j -q "$BUILD/base.apk" "$BUILD/dex/classes.dex"

echo "== 4/6 zipalign =="
zipalign -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "== 5/6 keystore =="
if [ ! -f "$BUILD/debug.keystore" ]; then
  keytool -genkeypair -keystore "$BUILD/debug.keystore" -alias qqbridge -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=QQ Bridge, OU=dev, O=dsh, L=CN, ST=CN, C=CN"
fi

echo "== 6/6 apksigner =="
mkdir -p "$(dirname "$OUT")"
apksigner sign --ks "$BUILD/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$BUILD/aligned.apk"
apksigner verify --print-certs "$OUT" | head -2
ls -la "$OUT"
