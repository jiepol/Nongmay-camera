#!/usr/bin/env bash
# Fetch the pinned offline toolchain used by tools/build_apk.sh (no Gradle / Google Maven needed).
#   - android.jar (API 35 stubs)         from github.com/Reginer/aosp-android-jar
#   - aapt2 (linux x86_64)               extracted from Apktool 2.11.1 release jar
#   - dx, zipalign, apksigner, junit4    from Ubuntu 24.04 packages
# Every download is checked against the SHA-256 recorded when v1.1 was built.
set -euo pipefail
TC="${NONGMAY_TOOLCHAIN:-$HOME/nongmay-toolchain}"
mkdir -p "$TC/dl"

fetch() { # url dest sha256
  local url="$1" dest="$2" sum="$3"
  if [ ! -f "$dest" ]; then curl -fsSL -m 600 -o "$dest" "$url"; fi
  echo "$sum  $dest" | sha256sum -c - >/dev/null || { echo "checksum mismatch: $dest" >&2; exit 1; }
}

fetch https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-35/android.jar \
      "$TC/android.jar" 60be6880d7b2b526d654460d4ce6fa81bf23efaa28b02d901d0b63a01ccf4671
fetch https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar \
      "$TC/dl/apktool.jar" 56d59c524fc764263ba8d345754d8daf55b1887818b15cd3b594f555d249e2db
if [ ! -x "$TC/aapt2_64" ]; then
  unzip -o -q -j "$TC/dl/apktool.jar" prebuilt/linux/aapt2_64 -d "$TC"
  chmod +x "$TC/aapt2_64"
fi
echo "e9f05c38c1b777d07b3c5a20b1b6ce66e110badc6e31384acd0aae53932b7322  $TC/aapt2_64" | sha256sum -c - >/dev/null

if command -v apt-get >/dev/null && [ "$(id -u)" = 0 ]; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q dalvik-exchange zipalign apksigner junit4 libhamcrest-java >/dev/null
fi
for t in dalvik-exchange zipalign apksigner javac keytool; do
  command -v "$t" >/dev/null || { echo "missing tool: $t" >&2; exit 1; }
done
echo "toolchain ready in $TC"
