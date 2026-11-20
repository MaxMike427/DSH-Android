#!/system/bin/sh
# Try installing one plugin into the web profile the app actually uses, and
# record what pnpm writes so the build can reproduce it.
PKG=com.dshapp

cat > /data/local/tmp/guest-add.sh <<'GUEST'
set -e
. /root/.nvm/nvm.sh
cd /root

echo "=== pnpm present?"
pnpm --version || echo "  pnpm MISSING"

echo "=== network?"
timeout 25 curl -sS -o /dev/null -w 'registry.npmjs.org: %{http_code}\n' https://registry.npmjs.org/dsh-token-diet || echo "  registry unreachable"

echo "=== install dsh-token-diet into the web profile"
dsh plugin --profile web add dsh-token-diet 2>&1 | tail -15

echo "=== resulting package.json"
cat /root/.dsh/profiles/web/package.json

echo "=== node_modules top level"
ls /root/.dsh/profiles/web/node_modules 2>/dev/null | head -20
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-add.sh > files/rootfs/root/guest-add.sh'
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp -w /root \
    -b /data/local/tmp:/data/local/tmp \
    /usr/bin/env -i HOME=/root PATH=/root/.nvm/versions/node/v24.22.3/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash /root/guest-add.sh
' 2>&1 | tail -45