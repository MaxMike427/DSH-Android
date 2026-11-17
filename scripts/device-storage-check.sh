#!/system/bin/sh
# Verify the guest can see and write Android shared storage.
#
# The mount list alone does not prove access: PRoot binds the path, but the app
# uid still needs the storage grant to read it. This checks both directions.
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
    -b /storage/emulated/0/Download:/mnt/android/download \
    -b /storage/emulated/0:/mnt/android/shared \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "
      echo === read Download
      ls /mnt/android/download 2>&1 | head -4
      echo
      echo === read shared root
      ls /mnt/android/shared 2>&1 | head -6
      echo
      echo === write probe
      echo dsh-probe > /mnt/android/download/dsh-probe.txt 2>&1 && echo WRITE_OK || echo WRITE_FAIL
      cat /mnt/android/download/dsh-probe.txt 2>&1
      rm -f /mnt/android/download/dsh-probe.txt
    "
' 2>&1 | tail -20
