#!/usr/bin/env bash
# Builds the static dsh-root-exec helper for the root runtime.
#
#   tooling/build-root-exec.sh                 # both ABIs -> app/build/root-exec/<abi>
#   tooling/build-root-exec.sh arm64-v8a       # one ABI
#   ANDROID_NDK_HOME=/opt/ndk tooling/build-root-exec.sh
#
# The helper must be static: it runs before chroot, where the guest's glibc
# loader is not reachable from the host namespace.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/.." && pwd)
src="$repo/app/android/app/src/main/cpp/dsh-root-exec.c"
out="$repo/app/build/root-exec"

ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [[ -z $ndk ]]; then
  # Fall back to the SDK's side-by-side install, newest first.
  for candidate in "$HOME"/Android/Sdk/ndk/* "$HOME"/Library/Android/sdk/ndk/*; do
    [[ -d $candidate ]] && ndk=$candidate
  done
fi
if [[ -z $ndk || ! -d $ndk ]]; then
  echo "build-root-exec: set ANDROID_NDK_HOME to an installed NDK" >&2
  exit 2
fi

host=$(uname -s | tr '[:upper:]' '[:lower:]')
case $host in
  linux) host=linux-x86_64 ;;
  darwin) host=darwin-x86_64 ;;
  *) echo "build-root-exec: unsupported host $host" >&2; exit 2 ;;
esac
clang="$ndk/toolchains/llvm/prebuilt/$host/bin/clang"
[[ -x $clang ]] || { echo "build-root-exec: no clang at $clang" >&2; exit 2; }

build() {
  local abi=$1 target=$2
  mkdir -p "$out/$abi"
  # The output is named lib*.so so Gradle packages it like a native library;
  # it is an ET_EXEC file, not a shared object.
  "$clang" --target="$target" -static -O2 -fno-stack-protector -s \
    -o "$out/$abi/libdsh-root-exec.so" "$src"
  echo "build-root-exec: $abi -> $out/$abi/libdsh-root-exec.so"
}

case ${1:-all} in
  arm64-v8a|all) build arm64-v8a aarch64-linux-android26 ;;
esac
case ${1:-all} in
  x86_64|all) build x86_64 x86_64-linux-android26 ;;
esac
