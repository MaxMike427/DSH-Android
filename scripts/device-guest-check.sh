#!/system/bin/sh
# Run a real command inside the Ubuntu rootfs through PRoot, as the app uid.
#
# This is the end-to-end check the app performs: PRoot binary + loader from
# files/usr, rootfs at files/rootfs, bind mounts for /dev /proc /sys, and a
# guest command executed inside.
set -e

PKG=com.dshapp
echo "=== guest check: ubuntu os-release"
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  mkdir -p tmp
  usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys \
    -b $PWD/home:/root \
    -b $PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc ". /etc/os-release; echo OS=\$PRETTY_NAME; uname -m; id"
' 2>&1 | tail -12
