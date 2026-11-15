#!/system/bin/sh
# Stage the PRoot prefix as the app uid and run it.
#
# `adb shell` is not root here, and the app uid cannot read /data/local/tmp, so
# the payload is streamed through a pipe: the shell reads it, run-as writes it.
# Execution still happens as the app uid, which is the context that matters.
set -e

PKG=com.dshapp
SRC=/data/local/tmp/pfx/usr

echo "=== clear prefix"
run-as $PKG sh -c "rm -rf files/usr; mkdir -p files/usr"

echo "=== stream payload into the app directory"
# tar over stdin: shell side reads (it can see /data/local/tmp), app side writes.
tar -cf - -C "$SRC" . | run-as $PKG sh -c "cd files/usr && tar -xf -"

echo "=== staged files"
run-as $PKG sh -c "ls -l files/usr/bin/proot files/usr/lib/libtalloc.so.2 files/usr/libexec/proot/loader"

echo "=== make executables runnable"
run-as $PKG sh -c "chmod 755 files/usr/bin/proot files/usr/libexec/proot/loader"

echo "=== run proot --version as the app uid"
run-as $PKG sh -c "cd files && PROOT_LOADER=\$PWD/usr/libexec/proot/loader PROOT_TMP_DIR=\$PWD/tmp usr/bin/proot --version" 2>&1 | head -6
