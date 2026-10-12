#!/usr/bin/env bash
set -e

GO_EXEC="${GO_EXEC:-go}"
NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}"

if [ -z "$NDK_HOME" ]; then
    echo "Error: ANDROID_NDK_HOME or ANDROID_NDK_ROOT is not set."
    exit 1
fi

HOST_OS="linux-x86_64"
if [[ "$OSTYPE" == "darwin"* ]]; then
    HOST_OS="darwin-x86_64"
elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
    HOST_OS="windows-x86_64"
fi

NDK_BIN="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_OS/bin"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

targets=("arm64-v8a:arm64::aarch64-linux-android24-clang" "armeabi-v7a:arm:7:armv7a-linux-androideabi24-clang" "x86_64:amd64::x86_64-linux-android24-clang")
cSrc="$REPO_ROOT/app/src/main/native/tcprelay/tcprelay.c"

for target in "${targets[@]}"; do
    IFS=":" read -r abi arch arm cc <<< "$target"
    outDir="$REPO_ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$outDir"

    echo "Compiling pure C libtcprelay.so for $abi with 16KB alignment..."
    "$NDK_BIN/$cc" -shared -fPIC -O3 -Wall -Wl,-z,max-page-size=16384 "$cSrc" -o "$outDir/libtcprelay.so" -llog

    echo "Building Go libtailcat.so for $abi with 16KB alignment..."
    (cd "$REPO_ROOT/app/src/main/native/tailcat-bridge" && GOOS=android GOARCH=$arch GOARM=$arm CGO_ENABLED=1 CC="$NDK_BIN/$cc" $GO_EXEC build -buildmode=c-shared -ldflags="-extldflags=-Wl,-z,max-page-size=16384" -o "$outDir/libtailcat.so" main.go)
done

echo "All native modules built successfully from project repository!"
