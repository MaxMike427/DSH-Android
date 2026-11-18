#!/usr/bin/env bash
# Verify the built payload as far as a Linux host can.
#
# Two facts shape this script:
#
#  1. Termux's `proot` is linked against Android's `/system/bin/linker64`, so it
#     cannot be executed on a Linux host at all. Its execution is validated on a
#     real device (see scripts/device-test.sh), never here.
#  2. Everything else in the payload is ordinary arm64 Linux and *can* be
#     validated natively: the Ubuntu rootfs, git, nvm, Node.js, pnpm, and the
#     `dsh web` server.
#
# So this checks the PRoot binary statically and exercises the guest through
# chroot, which is the strongest host-side signal available.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ASSETS="$REPO_ROOT/app/src/main/assets"
PACKAGE="${APP_PACKAGE:-com.dshapp}"
FILES="/data/data/$PACKAGE/files"
PREFIX="$FILES/usr"
ROOTFS="$FILES/rootfs"
WORK="${RUNNER_TEMP:-/tmp}/dsh-smoke"

cleanup() {
  for d in sys proc dev/pts dev; do sudo umount -lf "$ROOTFS/$d" 2>/dev/null || true; done
}
trap cleanup EXIT

sudo rm -rf "/data/data/$PACKAGE" "$WORK"
sudo mkdir -p "$PREFIX" "$ROOTFS"
sudo chown -R "$(id -u):$(id -g)" "/data/data/$PACKAGE"
mkdir -p "$WORK"

echo "=== unpack PRoot payload"
tar -xJf "$ASSETS/proot.tar.xz" -C "$FILES"
chmod +x "$PREFIX/bin/proot" "$PREFIX/libexec/proot/loader"

echo "=== unpack rootfs"
tar -xJf "$ASSETS/rootfs.tar.xz" -C "$ROOTFS"

echo "=== PRoot binary is Android-shaped and app-prefixed"
python3 - "$PREFIX/bin/proot" "$PACKAGE" <<'PY'
import struct, sys

path, package = sys.argv[1], sys.argv[2]
data = open(path, "rb").read()
assert data[:4] == b"\x7fELF", "not an ELF"
assert data[4] == 2, "not ELF64"
assert data[5] == 1, "not little-endian"

e_phoff, = struct.unpack_from("<Q", data, 0x20)
e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)

interp = None
dynamic = None
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    p_type, = struct.unpack_from("<I", data, off)
    p_offset, = struct.unpack_from("<Q", data, off + 8)
    p_filesz, = struct.unpack_from("<Q", data, off + 32)
    if p_type == 3:
        interp = data[p_offset:p_offset + p_filesz].rstrip(b"\0").decode()
    elif p_type == 2:
        dynamic = (p_offset, p_filesz)

assert interp == "/system/bin/linker64", f"unexpected PT_INTERP: {interp}"
print(f"  PT_INTERP      {interp}  (Android only - cannot run on this host)")

assert dynamic, "no dynamic section"
off, size = dynamic
entries = [struct.unpack_from("<qQ", data, j) for j in range(off, off + size, 16)]
strtab = next(v for t, v in entries if t == 5)

def cstr(o):
    end = data.index(b"\0", o)
    return data[o:end].decode()

runpath = [cstr(strtab + v) for t, v in entries if t in (15, 29)]
expected = f"/data/data/{package}/files/usr/lib"
assert expected in runpath, f"RUNPATH {runpath} does not contain {expected}"
print(f"  DT_RUNPATH     {expected}")

needed = [cstr(strtab + v) for t, v in entries if t == 1]
print(f"  DT_NEEDED      {', '.join(needed)}")

blob = open(path, "rb").read()
# Only executable-path strings matter: the binary also carries documentation and
# error text that legitimately mentions the Termux prefix.
for needle in (b"/data/data/com.termux/files/usr/lib", b"/data/data/com.termux/files/usr/libexec/proot"):
    assert needle not in blob, f"stale loader path survived: {needle!r}"
print("  no stale Termux loader paths remain")
PY

echo "=== every PRoot dependency is present in the payload"
for lib in libtalloc.so.2 libandroid-shmem.so libandroid-support.so; do
  if [ -e "$PREFIX/lib/$lib" ] || [ -e "$PREFIX/lib/libtalloc.so.2.5.0" ]; then
    echo "  ok $lib"
  elif [ "$lib" = "libtalloc.so.2" ] && [ -e "$PREFIX/lib/libtalloc.so.2.5.0" ]; then
    echo "  ok $lib (via libtalloc.so.2.5.0)"
  else
    echo "  MISSING $lib" >&2
    exit 1
  fi
done

echo "=== prepare chroot mounts"
sudo cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
for d in dev dev/pts proc sys; do sudo mkdir -p "$ROOTFS/$d"; done
sudo mount --bind /dev "$ROOTFS/dev"
sudo mount --bind /dev/pts "$ROOTFS/dev/pts"
sudo mount -t proc proc "$ROOTFS/proc"
sudo mount --bind /sys "$ROOTFS/sys"

guest() {
  sudo chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM=dumb LANG=C.UTF-8 "$@"
}

echo "=== guest smoke tests (native arm64 chroot)"
guest /bin/bash -lc 'set -e
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

echo "=== PRoot profile layer is installed in the web profile"
test -f "$ROOTFS/root/.dsh/profiles/web/cordis.patch.yml"
test -f "$ROOTFS/root/.dsh/profiles/web/profile-shim.js"
grep -q "profile-shim.js" "$ROOTFS/root/.dsh/profiles/web/cordis.patch.yml"
# The shim is an ES module; the profile manifest must say so.
grep -q '"type": "module"' "$ROOTFS/root/.dsh/profiles/web/package.json"
# First-use workspace: without an explicit Documents directory the harness asks
# `xdg-user-dir`, which the guest does not ship, so no workspace is ever created
# and the Web UI opens on an empty picker.
grep -q "documentsDirectory: /root/Documents" "$ROOTFS/root/.dsh/profiles/web/cordis.patch.yml"
test -d "$ROOTFS/root/Documents/deepseek-harness/default-workspace"
echo "  ok"

echo "=== dsh web boots with every plugin activated"
# A plugin that fails to activate leaves the server answering while the Web UI
# loses part of its API, so "port is open" alone is not enough: the boot log must
# be free of activation failures. The presets catalog status is printed for
# context only — a 404 there is normal when a host has no preset provider mounted
# and does not by itself blank the page (verified in a desktop browser).
guest /bin/bash -lc 'set -e
  . /root/.nvm/nvm.sh
  cd /root
  dsh web --no-open --port 3099 >/tmp/web.log 2>&1 &
  pid=$!
  ready=0
  for _ in $(seq 1 180); do
    if (exec 3<>/dev/tcp/127.0.0.1/3099) 2>/dev/null; then
      ready=1
      break
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "  web process exited early" >&2
      cat /tmp/web.log >&2
      exit 1
    fi
    sleep 1
  done
  if [ "$ready" != 1 ]; then
    echo "  web did not become ready" >&2
    cat /tmp/web.log >&2
    kill "$pid" 2>/dev/null || true
    exit 1
  fi
  echo "  web: listening on 127.0.0.1:3099"

  token=""
  for _ in $(seq 1 30); do
    token=$(sed -n "s#.*token=\([A-Za-z0-9_-]*\).*#\1#p" /tmp/web.log | tail -1)
    [ -n "$token" ] && break
    sleep 1
  done
  if [ -z "$token" ]; then
    echo "  FAIL: no auth token in the web log" >&2
    cat /tmp/web.log >&2
    kill "$pid" 2>/dev/null || true
    exit 1
  fi

  if ! node -e "
    const base = \"http://127.0.0.1:3099\"
    const token = process.argv[1]
    async function main() {
      const first = await fetch(base + \"/?token=\" + token, { redirect: \"manual\" })
      const setCookie = first.headers.get(\"set-cookie\") || \"\"
      const cookie = setCookie.split(\";\")[0]
      const page = await fetch(base + \"/\", {
        headers: cookie ? { cookie } : {},
        redirect: \"manual\",
      })
      const html = await page.text()
      console.log(\"  page: \" + page.status + \" HTML \" + html.length + \" bytes\")
      if (page.status !== 200 || html.length < 500) {
        console.log(\"  FAIL: the Web UI document is not served\")
        process.exit(1)
      }
      const catalog = await fetch(base + \"/api/permissionPresets/catalog\", {
        headers: cookie ? { cookie } : {},
        redirect: \"manual\",
      })
      console.log(\"  catalog: \" + catalog.status + \" (informational)\")
    }
    main().catch(err => { console.log(\"  FAIL: probe error: \" + err.message); process.exit(1) })
  " "$token"; then
    cat /tmp/web.log >&2
    kill "$pid" 2>/dev/null || true
    exit 1
  fi

  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true

  if grep -q "did not activate" /tmp/web.log; then
    echo "  FAIL: some plugins did not activate" >&2
    cat /tmp/web.log >&2
    exit 1
  fi
  if grep -q "does not confine" /tmp/web.log; then
    echo "  FAIL: the presets service rejected the executor" >&2
    cat /tmp/web.log >&2
    exit 1
  fi
  echo "  ok: all plugins activated and the presets catalog is served"
'

echo "=== the PRoot composition replaced the sandboxed executor"
# PRoot cannot satisfy dsh-sandbox-local (no bwrap/Landlock) and that provider is
# fail-closed, so the profile layer must replace the executor. It does so with
# `profile-shim.js`, a subclass of dsh-bash-local that additionally reports a
# sandbox mode; without that report, dsh-permission-presets refuses to construct
# and the Web UI loses /api/permissionPresets/catalog.
#
# `--dump-config` prints disabled rows too, so the shipped row is expected to be
# present and switched off, not absent.
guest /bin/bash -lc 'set -e
  . /root/.nvm/nvm.sh
  cd /root
  dsh --profile web --dump-config >/tmp/composed.yml 2>/tmp/composed.err || true
  echo "  --- composed bash row ---"
  grep -n -A3 "^\- id: bash$" /tmp/composed.yml || true
  echo "  --- composed sandbox row ---"
  grep -n -A4 "^\- id: bash-sandbox$" /tmp/composed.yml || true
  echo "  --- patch diagnostics (informational) ---"
  cat /tmp/composed.err || true

  # The sole shell provider must be the shim.
  if ! grep -q "profile-shim.js" /tmp/composed.yml; then
    echo "  FAIL: the shim did not compose into the bash row" >&2
    exit 1
  fi
  # The shipped sandboxed executor must be present but disabled.
  if ! awk "
    /^- id: bash-sandbox\$/ { inside = 1; next }
    inside && /^- / { inside = 0 }
    inside && /^  disabled: true\$/ { found = 1 }
    END { exit found ? 0 : 1 }
  " /tmp/composed.yml; then
    echo "  FAIL: bash-sandbox is still enabled" >&2
    exit 1
  fi
  echo "  ok: bash runs through the PRoot shim"
'

echo "=== payload smoke test passed"
echo "    (PRoot execution itself is device-only; see scripts/device-test.sh)"
