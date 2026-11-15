#!/usr/bin/env bash
# Verify the built payload the way the Android app consumes it.
#
# PRoot's DT_RUNPATH and PROOT_UNBUNDLE_LOADER are absolute
# (/data/data/com.dshapp/files/usr/...), exactly as they will be on the device,
# so the payload is unpacked at that path rather than under a temp directory.
# This needs sudo on the CI runner; nothing outside that path is modified.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ASSETS="$REPO_ROOT/app/src/main/assets"
PACKAGE="${APP_PACKAGE:-com.dshapp}"
FILES="/data/data/$PACKAGE/files"
PREFIX="$FILES/usr"
ROOTFS="$FILES/rootfs"
WORK="${RUNNER_TEMP:-/tmp}/dsh-smoke"

sudo rm -rf "/data/data/$PACKAGE" "$WORK"
sudo mkdir -p "$PREFIX" "$ROOTFS"
sudo chown -R "$(id -u):$(id -g)" "/data/data/$PACKAGE"
mkdir -p "$WORK"

echo "=== unpack PRoot payload"
tar -xzf "$ASSETS/proot.tar.gz" -C "$FILES"
chmod +x "$PREFIX/bin/proot" "$PREFIX/libexec/proot/loader"

echo "=== unpack rootfs"
tar -xzf "$ASSETS/rootfs.tar.gz" -C "$ROOTFS"

echo "=== prefix rewrite check"
if grep -qF "/data/data/com.termux/files/usr" "$PREFIX/bin/proot"; then
  echo "proot still references com.termux" >&2
  exit 1
fi
grep -qF "/data/data/$PACKAGE/files/usr" "$PREFIX/bin/proot"
echo "ok: proot resolves $PREFIX"

export PROOT_TMP_DIR="$WORK/tmp"
mkdir -p "$PROOT_TMP_DIR"
export PROOT_LOADER="$PREFIX/libexec/proot/loader"
export PROOT_NO_SECCOMP=1

run_guest() {
  "$PREFIX/bin/proot" \
    --kill-on-exit \
    --link2symlink \
    -0 \
    -r "$ROOTFS" \
    -b /dev -b /proc -b /sys \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "$1"
}

echo "=== guest smoke tests"
run_guest 'set -e
  . /root/.nvm/nvm.sh
  echo "os:     $(. /etc/os-release; echo "$PRETTY_NAME")"
  echo "kernel: $(uname -r)"
  echo "git:    $(git --version)"
  echo "nvm:    $(nvm --version)"
  echo "node:   $(node --version)"
  echo "npm:    $(npm --version)"
  echo "pnpm:   $(pnpm --version)"
  echo "dsh:    $(dsh --version)"
  cd /root
  rm -rf smoke && git init -q smoke && cd smoke
  echo hi > a.txt
  git add a.txt
  git -c user.email=a@b -c user.name=a commit -qm init
  git log --oneline
'

echo "=== profile patch is installed"
run_guest 'test -f /opt/dsh/cordis.patch.yml && grep -q "bash-local" /opt/dsh/cordis.patch.yml && echo ok'

echo "=== dsh web boot with the PRoot profile patch"
run_guest 'set -e
  . /root/.nvm/nvm.sh
  cd /root
  dsh web --no-open --port 3099 --patch /opt/dsh/cordis.patch.yml >/tmp/web.log 2>&1 &
  pid=$!
  for _ in $(seq 1 150); do
    if (exec 3<>/dev/tcp/127.0.0.1/3099) 2>/dev/null; then
      echo "web: listening on 127.0.0.1:3099"
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      exit 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "web process exited early" >&2
      cat /tmp/web.log >&2
      exit 1
    fi
    sleep 1
  done
  echo "web did not become ready" >&2
  cat /tmp/web.log >&2
  kill "$pid" 2>/dev/null || true
  exit 1
'

echo "=== payload smoke test passed"
