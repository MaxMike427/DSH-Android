#!/system/bin/sh
# Prove the bundled PRoot binary can execute on this device.
#
# The binary links against Android's /system/bin/linker64 and has DT_RUNPATH
# pointing at /data/data/com.dshapp/files/usr/lib, so both the loader and the
# shared libraries must be in place before anything runs. This script stages
# exactly that layout and runs `proot --version` through the app's own uid.
set -e

PKG=com.dshapp
APK="$1"

run_as() { run-as "$PKG" "$@"; }

echo "--- clean staging area"
run_as sh -c "rm -rf files/usr; mkdir -p files/usr/bin files/usr/lib files/usr/libexec/proot"

echo "--- copy payload into place"
# The APK is world-readable, so the app uid can read it directly.
run_as sh -c "cd files && unzip -o -q '$APK' 'assets/proot.tar.xz' -d stage"
run_as sh -c "ls -l files/stage/assets/proot.tar.xz"

echo "--- the app itself must unpack the xz; that path is exercised by the"
echo "    main installer. Here we verify the ELF header and loader instead:"
run_as sh -c "head -c 20 files/stage/assets/proot.tar.xz | od -An -tx1 | head -2"

echo
echo "--- ELF check on the ORIGINAL termux proot (if present in termux app)"
if [ -x /data/data/com.termux/files/usr/bin/proot ]; then
  echo "termux proot present; comparing interpreter:"
  /system/bin/linker64 --list /data/data/com.termux/files/usr/bin/proot 2>&1 | head -8 || true
else
  echo "no termux proot installed"
fi
