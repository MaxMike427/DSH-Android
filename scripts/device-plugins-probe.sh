#!/system/bin/sh
# Inspect how profiles and plugin installation work inside the guest.
PKG=com.dshapp

cat > /data/local/tmp/guest-plugins.sh <<'GUEST'
set -e
. /root/.nvm/nvm.sh
cd /root

echo "=== profiles present"
ls -1 /root/.dsh/profiles/

echo "=== web profile files"
ls -la /root/.dsh/profiles/web/

echo "=== web profile package.json"
cat /root/.dsh/profiles/web/package.json

echo "=== desktop profile package.json (if any)"
cat /root/.dsh/profiles/desktop/package.json 2>/dev/null || echo "  (no desktop profile)"

echo "=== cordis.yml of web"
cat /root/.dsh/profiles/web/cordis.yml

echo "=== dsh plugin --help"
dsh plugin --help 2>&1 | head -40
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-plugins.sh > files/rootfs/root/guest-plugins.sh'
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash /root/guest-plugins.sh
' 2>&1 | tail -70