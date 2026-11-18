#!/system/bin/sh
# Find the composed config row that mounts the workspace controller, and check
# whether the first-use Documents lookup can work inside the guest.
PKG=com.dshapp

cat > /data/local/tmp/guest-workspace.sh <<'GUEST'
set -e
. /root/.nvm/nvm.sh
cd /root

echo "=== composed row owning workspaceController"
dsh --profile web --dump-config 2>/dev/null | grep -n -B3 -A5 'workspace-controller' | head -30 || true

echo "=== can the harness resolve Documents? (xdg-user-dir)"
if command -v xdg-user-dir >/dev/null 2>&1; then
  echo "xdg-user-dir: $(command -v xdg-user-dir)"
  xdg-user-dir DOCUMENTS || echo "  (lookup failed)"
else
  echo "xdg-user-dir: MISSING -- first-use workspace creation cannot resolve Documents"
fi

echo "=== visible entries in /root"
ls -A /root | sed 's/^/  /'
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-workspace.sh > files/rootfs/root/guest-workspace.sh'
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash /root/guest-workspace.sh
' 2>&1 | tail -30
