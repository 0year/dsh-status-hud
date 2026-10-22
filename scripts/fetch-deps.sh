#!/bin/bash
# 下载手动打包 APK 所需的依赖（无需 Android Studio / Gradle）。
# 产物全部放进 android/build/，build.sh 会直接使用。
set -e
cd "$(dirname "$0")/.."
ANDROID=android
BUILD=$ANDROID/build
mkdir -p "$BUILD"

SDK_ZIP_URL="https://dl.google.com/android/repository/platform-29_r05.zip"
BT_ZIP_URL="https://dl.google.com/android/repository/build-tools_r35_linux.zip"
MZ="https://repo1.maven.org/maven2/dev/rikka/shizuku"

echo "== 1/2 Android platform (android.jar, API 29) =="
if [ ! -f "$BUILD/android.jar" ]; then
  curl -L --retry 3 -o "$BUILD/platform-29.zip" "$SDK_ZIP_URL"
  unzip -o -q "$BUILD/platform-29.zip" android-10/android.jar -d "$BUILD/tmp-platform"
  cp "$BUILD/tmp-platform/android-10/android.jar" "$BUILD/android.jar"
  rm -rf "$BUILD/platform-29.zip" "$BUILD/tmp-platform"
fi
echo "  -> $BUILD/android.jar"

echo "== 2/2 build-tools d8 + Shizuku API 13.1.5 =="
if [ ! -f "$BUILD/d8.jar" ]; then
  curl -L --retry 3 -o "$BUILD/build-tools.zip" "$BT_ZIP_URL"
  unzip -o -q "$BUILD/build-tools.zip" "*/lib/d8.jar" -d "$BUILD/tmp-bt"
  cp "$(find "$BUILD/tmp-bt" -name d8.jar | head -1)" "$BUILD/d8.jar"
  rm -rf "$BUILD/build-tools.zip" "$BUILD/tmp-bt"
fi
echo "  -> $BUILD/d8.jar"

for art in api provider aidl shared; do
  if [ ! -f "$BUILD/shizuku-$art.jar" ]; then
    curl -L --retry 3 -o "$BUILD/$art.aar" "$MZ/$art/13.1.5/$art-13.1.5.aar"
    unzip -o -q "$BUILD/$art.aar" classes.jar -d "$BUILD/tmp-$art"
    cp "$BUILD/tmp-$art/classes.jar" "$BUILD/shizuku-$art.jar"
    rm -rf "$BUILD/$art.aar" "$BUILD/tmp-$art"
  fi
  echo "  -> $BUILD/shizuku-$art.jar"
done

echo "✅ 依赖就绪，可以运行 android/build.sh"
