#!/system/bin/sh
# Prove the guest can reach the Tsinghua mirror and refresh its package index.
PKG=com.dshapp

run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "
      echo === sources
      grep tuna /etc/apt/sources.list | head -2
      echo
      echo === apt-get update
      apt-get update 2>&1 | tail -12
    "
' 2>&1 | tail -20
