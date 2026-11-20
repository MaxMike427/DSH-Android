#!/system/bin/sh
# Which interpreters are available inside the PRoot guest? The build-rootfs.sh
# patch step runs inside the chroot, so it may only use what is really there.
PKG=com.dshapp

cat > /data/local/tmp/guest-which.sh <<'GUEST'
for t in python3 python perl node sed awk; do
  printf '%s: ' "$t"
  command -v "$t" || echo MISSING
done
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-which.sh > files/rootfs/root/guest-which.sh; chmod 0644 files/rootfs/root/guest-which.sh'
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/root/.nvm/versions/node/v24.22.3/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/sh /root/guest-which.sh
' 2>&1 | tail -8
