#!/usr/bin/env bash
# On-device validation. PRoot cannot run on a Linux host (it links against
# Android's /system/bin/linker64), so this is the only place the real path is
# exercised.
#
# Usage:
#   adb install -r app-debug.apk
#   adb shell am start -n com.dshapp/com.dsh.android.MainActivity
#   bash scripts/device-test.sh
#
# The app extracts the payload on first launch; wait for the WebView to load
# before running this, or pass --wait to poll.
set -euo pipefail

PACKAGE="com.dshapp"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB+=( -s "$SERIAL" )

say() { printf '\n=== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

say "device"
"${ADB[@]}" wait-for-device
"${ADB[@]}" shell getprop ro.product.cpu.abi
"${ADB[@]}" shell getprop ro.build.version.release
"${ADB[@]}" shell getprop ro.build.version.sdk

say "app installed"
"${ADB[@]}" shell pm list packages | grep -qx "package:$PACKAGE" || fail "$PACKAGE not installed"

say "payload extracted"
FILES="/data/data/$PACKAGE/files"
"${ADB[@]}" shell "run-as $PACKAGE ls -d $FILES/usr $FILES/rootfs $FILES/state/installed.json" \
  || fail "runtime not installed yet - launch the app and wait for extraction"

say "PRoot runs inside the app sandbox"
# run-as executes with the app's uid, which is exactly how the app spawns PRoot.
"${ADB[@]}" shell "run-as $PACKAGE sh -c 'cd $FILES && PROOT_TMP_DIR=$FILES/tmp PROOT_NO_SECCOMP=1 PROOT_LOADER=$FILES/usr/libexec/proot/loader $FILES/usr/bin/proot --version'" \
  || fail "proot --version failed"

say "guest toolchain versions"
"${ADB[@]}" shell "run-as $PACKAGE sh -c 'cd $FILES && PROOT_TMP_DIR=$FILES/tmp PROOT_NO_SECCOMP=1 PROOT_LOADER=$FILES/usr/libexec/proot/loader $FILES/usr/bin/proot --kill-on-exit --link2symlink -0 -r $FILES/rootfs -b /dev -b /proc -b /sys -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb /bin/bash -lc \". /root/.nvm/nvm.sh; . /etc/os-release; echo os=\\\$PRETTY_NAME; uname -r; git --version; nvm --version; node --version; npm --version; pnpm --version; dsh --version\"'"

say "git works in the guest"
"${ADB[@]}" shell "run-as $PACKAGE sh -c 'cd $FILES && PROOT_TMP_DIR=$FILES/tmp PROOT_NO_SECCOMP=1 PROOT_LOADER=$FILES/usr/libexec/proot/loader $FILES/usr/bin/proot --kill-on-exit --link2symlink -0 -r $FILES/rootfs -b /dev -b /proc -b /sys -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -lc \"cd /root && rm -rf dt && git init -q dt && cd dt && echo hi > a && git add a && git -c user.email=a@b -c user.name=a commit -qm init && git log --oneline\"'"

say "shared storage is mounted read-write"
# Writes a probe file through the guest and reads it back from the Android side,
# which proves the bind actually crosses the sandbox boundary.
STORAGE_TEST="/storage/emulated/0/Download/dsh-mount-test.txt"
if "${ADB[@]}" shell "run-as $PACKAGE ls $FILES/rootfs >/dev/null 2>&1"; then :; fi
"${ADB[@]}" shell "run-as $PACKAGE sh -c 'cd $FILES && PROOT_TMP_DIR=$FILES/tmp PROOT_NO_SECCOMP=1 PROOT_LOADER=$FILES/usr/libexec/proot/loader $FILES/usr/bin/proot --kill-on-exit --link2symlink -0 -r $FILES/rootfs -b /dev -b /proc -b /sys -b /storage/emulated/0/Download:/mnt/android/download -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -lc \"echo dsh-ok > /mnt/android/download/dsh-mount-test.txt && cat /mnt/android/download/dsh-mount-test.txt\"'" \
  || fail "could not write through /mnt/android/download (is all-files access granted?)"
"${ADB[@]}" shell "cat $STORAGE_TEST" | grep -q "dsh-ok" \
  || fail "guest write did not reach $STORAGE_TEST"
echo "  guest wrote $STORAGE_TEST"
"${ADB[@]}" shell "rm -f $STORAGE_TEST" || true

say "dsh web is serving"
"${ADB[@]}" shell "run-as $PACKAGE sh -c 'cat $FILES/logs/dsh-web.log | tail -n 20'" || true
"${ADB[@]}" forward tcp:3080 tcp:3080 >/dev/null
if curl -fsS --max-time 10 http://127.0.0.1:3080/ >/dev/null; then
  echo "  HTTP 200 from the Web UI"
else
  fail "Web UI did not answer on 127.0.0.1:3080 (is the app in the foreground?)"
fi

say "pass"
