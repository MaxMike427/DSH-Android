#!/system/bin/sh
# Install the requested plugin set into the web profile and report each result.
# Runs on the device so the real registry and the real pnpm are exercised before
# the build script is written.
PKG=com.dshapp

cat > /data/local/tmp/guest-addall.sh <<'GUEST'
. /root/.nvm/nvm.sh
cd /root

add() {
  name="$1"
  printf '\n=== %s\n' "$name"
  if out=$(dsh plugin --profile web add "$name" 2>&1); then
    printf '%s\n' "$out" | tail -4
  else
    printf 'FAILED\n%s\n' "$(printf '%s' "$out" | tail -12)"
    return 1
  fi
}

add dsh-pet
add dshmarket
add dsh-context
add dsh-token-diet
add billion-context
add @liustack/modlens
add dsh-at-file
add dsh-genui
add dsh-soul-md
add "dsh-speak-up@https://github.com/Yuuyuko-uu/dsh-speak-up/tarball/main"

printf '\n=== final bundles\n'
cat /root/.dsh/profiles/web/package.json
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-addall.sh > files/rootfs/root/guest-addall.sh'
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/root/.nvm/versions/node/v24.22.3/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash /root/guest-addall.sh
' 2>&1 | tail -80