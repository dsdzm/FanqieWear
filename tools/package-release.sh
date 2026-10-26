#!/usr/bin/env bash
#
# 一次构建，产出两个签名版本（发版固定产物）：
#
#   dist/FanqieWear-v<VER>-release.apk  官方密钥（keystore/shuge-release.jks），普通安装用这个
#   dist/FanqieWear-v<VER>-testkey.apk  AOSP testkey，仅供定制系统 / 系统分区使用
#
# 两个版本签名证书不同，设备上不能互相覆盖安装：换版本要先卸载。
#
# 用法：
#   tools/package-release.sh
# 可选环境变量：
#   ANDROID_SDK_ROOT  SDK 路径（默认 ~/Android/Sdk）
#   ANDROID_NDK_ROOT  NDK 路径，设置后会传给 Gradle：-Pandroid.ndkPath=...
#
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/dist"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"

BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
if [ -z "${BT:-}" ]; then
    echo "找不到 build-tools，请先设置 ANDROID_SDK_ROOT" >&2
    exit 1
fi
APKSIGNER="$BT/apksigner"

NDK_ARGS=()
if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
    NDK_ARGS+=("-Pandroid.ndkPath=$ANDROID_NDK_ROOT")
fi

VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)
if [ -z "$VER" ]; then
    echo "读不到 versionName" >&2
    exit 1
fi
echo "==> 构建 v$VER"

if [ -x "$ROOT/gradlew" ]; then
    (cd "$ROOT" && ./gradlew :app:assembleRelease --console=plain "${NDK_ARGS[@]+"${NDK_ARGS[@]}"}")
else
    (cd "$ROOT" && gradle :app:assembleRelease --console=plain "${NDK_ARGS[@]+"${NDK_ARGS[@]}"}")
fi

SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "构建产物不存在：$SRC" >&2; exit 1; }

mkdir -p "$OUT"
OFFICIAL="$OUT/FanqieWear-v$VER-release.apk"
TESTKEY="$OUT/FanqieWear-v$VER-testkey.apk"
cp "$SRC" "$OFFICIAL"

# AOSP testkey 是公开密钥，缺失时从 AOSP 镜像补一份，不往仓库里塞私钥文件
TK="$ROOT/keystore/testkey"
if [ ! -f "$TK/testkey.pk8" ] || [ ! -f "$TK/testkey.x509.pem" ]; then
    mkdir -p "$TK"
    echo "==> 拉取 AOSP testkey"
    curl -sL -o "$TK/testkey.pk8" \
        https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security/testkey.pk8
    curl -sL -o "$TK/testkey.x509.pem" \
        https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security/testkey.x509.pem
fi

"$APKSIGNER" sign \
    --key "$TK/testkey.pk8" --cert "$TK/testkey.x509.pem" \
    --out "$TESTKEY" "$OFFICIAL"
rm -f "$TESTKEY.idsig"

echo
echo "==> 官方密钥"
"$APKSIGNER" verify --print-certs "$OFFICIAL" | head -3
echo "==> testkey"
"$APKSIGNER" verify --print-certs "$TESTKEY" | head -3

echo
ls -la "$OUT"/FanqieWear-v"$VER"-*.apk
