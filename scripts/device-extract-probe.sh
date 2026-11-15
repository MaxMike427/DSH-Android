#!/system/bin/sh
# Extract the PRoot payload from the installed APK and run it.
#
# Runs as the app uid (via run-as) so the test exercises exactly the same
# execution context the app uses: private storage, targetSdk 28, SELinux
# untrusted_app. If this fails, the app cannot start either.
set -e

FILES="$1"
APK="$2"

mkdir -p "$FILES/usr"
cd "$FILES"

echo "--- extracting proot payload"
# toybox unzip can extract into the current directory (-d is supported).
unzip -o -q "$APK" 'assets/proot.tar.xz' -d "$FILES/state" 2>/dev/null || {
  echo "unzip failed"
  exit 1
}

ls -l "$FILES/state/assets/proot.tar.xz" 2>/dev/null || {
  echo "payload missing after extract"
  exit 1
}

echo "--- verifying xz superblock"
head -c 6 "$FILES/state/assets/proot.tar.xz" | od -An -tx1

echo "--- note: Android toybox tar has no xz support, so extraction"
echo "    is done by the app itself; this script only proves the payload"
echo "    is readable and the APK layout is correct."
