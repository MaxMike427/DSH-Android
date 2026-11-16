#!/system/bin/sh
# Stage PRoot into the app prefix and run a real guest command.
#
# Mirrors exactly what DshRuntime.prootCommand() does, so a failure here is the
# same failure the app would hit.
set -e

PKG=com.dshapp
SRC=/data/local/tmp/pfx2/usr

echo "=== stage payload (streamed through a pipe: app uid cannot read /data/local/tmp)"
run-as $PKG sh -c "rm -rf files/usr; mkdir -p files/usr files/tmp"
tar -cf - -C "$SRC" . | run-as $PKG sh -c "cd files/usr && tar -xf -"
run-as $PKG sh -c "chmod 755 files/usr/bin/proot files/usr/libexec/proot/loader"
run-as $PKG sh -c "ls -l files/usr/bin/proot files/usr/lib/libtalloc.so.2"

echo
echo "=== guest command through PRoot"
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys \
    -b $PWD/home:/root \
    -b $PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "cat /etc/os-release | head -2; uname -m; id; ls /"
' 2>&1 | tail -20
