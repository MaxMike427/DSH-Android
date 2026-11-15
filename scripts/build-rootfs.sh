#!/usr/bin/env bash
# Build the offline Android runtime payload.
#
# Must run on an ARM64 Linux host (GitHub Actions `ubuntu-24.04-arm`): the
# Ubuntu Base rootfs is extracted as-is and its own binaries are executed to
# install Node.js and DeepSeekRuntime. Cross-building under qemu is avoided on
# purpose - npm lifecycle scripts would run emulated and produce a payload we
# cannot smoke-test meaningfully.
#
# Outputs (consumed by the app from src/main/assets):
#   rootfs.tar.gz          Ubuntu 24.04.x arm64 + git/nvm/node/npm/pnpm/dsh
#   proot.tar.gz           Termux PRoot with the app prefix patched in
#   runtime-manifest.json  pinned versions and sizes
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/app/src/main/assets}"
WORK_DIR="${WORK_DIR:-${RUNNER_TEMP:-/tmp}/dsh-rootfs}"
UBUNTU_VERSION="${UBUNTU_VERSION:-24.04.5}"
NODE_MAJOR="${NODE_MAJOR:-24}"
PNPM_VERSION="${PNPM_VERSION:-12.18.0}"
DSH_VERSION="${DSH_VERSION:-0.2.2-rc.3}"
TERMUX_MIRROR="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
APP_PACKAGE="${APP_PACKAGE:-com.dshapp}"

mkdir -p "$OUT_DIR" "$WORK_DIR"

log() { printf '\n=== %s\n' "$*" >&2; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "missing host tool: $1" >&2; exit 1; }; }

for tool in curl tar gzip sha256sum sudo chroot python3 perl ar; do need "$tool"; done
[ "$(uname -m)" = "aarch64" ] || { echo "must run on aarch64 (got $(uname -m))" >&2; exit 1; }
[ "${#APP_PACKAGE}" -eq 10 ] || { echo "APP_PACKAGE must be 10 chars to match com.termux" >&2; exit 1; }

ROOTFS="$WORK_DIR/rootfs"
rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"

log "Ubuntu Base $UBUNTU_VERSION (arm64)"
BASE_TAR="$WORK_DIR/ubuntu-base-$UBUNTU_VERSION-base-arm64.tar.gz"
BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_VERSION/release/ubuntu-base-$UBUNTU_VERSION-base-arm64.tar.gz"
curl -fsSL --retry 3 -o "$BASE_TAR" "$BASE_URL"
EXPECTED="$(curl -fsSL "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_VERSION/release/SHA256SUMS" \
  | awk -v f="ubuntu-base-$UBUNTU_VERSION-base-arm64.tar.gz" '$2 == "*"f {print $1}')"
[ -n "$EXPECTED" ] || { echo "cannot resolve Ubuntu checksum" >&2; exit 1; }
echo "$EXPECTED  $BASE_TAR" | sha256sum -c -
sudo tar -xzf "$BASE_TAR" -C "$ROOTFS"

sudo cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
for d in dev dev/pts proc sys; do sudo mkdir -p "$ROOTFS/$d"; done
sudo mount --bind /dev "$ROOTFS/dev"
sudo mount --bind /dev/pts "$ROOTFS/dev/pts"
sudo mount -t proc proc "$ROOTFS/proc"
sudo mount --bind /sys "$ROOTFS/sys"
cleanup_mounts() {
  for d in sys proc dev/pts dev; do sudo umount -lf "$ROOTFS/$d" 2>/dev/null || true; done
}
trap cleanup_mounts EXIT

log "apt: base toolchain"
sudo chroot "$ROOTFS" /usr/bin/env -i \
  HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  DEBIAN_FRONTEND=noninteractive TERM=dumb LANG=C.UTF-8 \
  bash -lc 'set -e
    apt-get update
    apt-get install -y --no-install-recommends \
      bash ca-certificates curl git openssh-client less nano procps \
      tzdata unzip xz-utils zip libatomic1 libstdc++6
    apt-get clean
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*'

log "nvm + Node.js $NODE_MAJOR"
NVM_VERSION="$(curl -fsSL https://api.github.com/repos/nvm-sh/nvm/releases/latest \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['tag_name'])")"
export NVM_VERSION
sudo chroot "$ROOTFS" /usr/bin/env -i \
  HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  NVM_VERSION="$NVM_VERSION" NODE_MAJOR="$NODE_MAJOR" PNPM_VERSION="$PNPM_VERSION" \
  TERM=dumb LANG=C.UTF-8 \
  bash -lc 'set -e
    curl -fsSL "https://raw.githubusercontent.com/nvm-sh/nvm/$NVM_VERSION/install.sh" | bash
    export NVM_DIR=/root/.nvm
    . "$NVM_DIR/nvm.sh"
    nvm install "$NODE_MAJOR"
    nvm alias default "$NODE_MAJOR"
    npm install -g "pnpm@$PNPM_VERSION"
    corepack disable || true'

log "DeepSeekRuntime $DSH_VERSION"
sudo chroot "$ROOTFS" /usr/bin/env -i \
  HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  DSH_VERSION="$DSH_VERSION" TERM=dumb LANG=C.UTF-8 CI=1 \
  bash -lc 'set -e
    export NVM_DIR=/root/.nvm
    . "$NVM_DIR/nvm.sh"
    node --version
    npm install -g "@deepseek-ai/dsh@$DSH_VERSION"
    dsh --version'

# Warm the web profile so the first on-device launch needs no package downloads.
log "warm dsh web profile"
sudo chroot "$ROOTFS" /usr/bin/env -i \
  HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  TERM=dumb LANG=C.UTF-8 CI=1 \
  bash -lc 'set -e
    export NVM_DIR=/root/.nvm
    . "$NVM_DIR/nvm.sh"
    cd /root
    timeout 240 dsh web --no-open --port 3099 >/tmp/dsh-warm.log 2>&1 || true
    tail -n 60 /tmp/dsh-warm.log || true
    rm -rf /root/.npm /root/.cache
    ls -la /root/.dsh 2>/dev/null || true'

log "trim rootfs"
sudo chroot "$ROOTFS" /usr/bin/env -i \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  bash -lc 'set -e
    rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/locale/* /var/cache/* /tmp/* \
           /root/.npm /root/.cache /usr/lib/apt /var/lib/apt/lists
    apt-get clean || true'

# PRoot-specific composition: swap the bwrap/Landlock bash executor for the
# unconfined local one. Installed into the image so the app can pass it with
# --patch without shipping a separate asset.
log "install PRoot profile patch"
sudo mkdir -p "$ROOTFS/opt/dsh"
sudo cp "$REPO_ROOT/runtime/dsh/cordis.patch.yml" "$ROOTFS/opt/dsh/cordis.patch.yml"
sudo chmod 0644 "$ROOTFS/opt/dsh/cordis.patch.yml"

cleanup_mounts
trap - EXIT

log "Termux PRoot"
PROOT_DIR="$WORK_DIR/proot"
rm -rf "$PROOT_DIR"
mkdir -p "$PROOT_DIR/usr/bin" "$PROOT_DIR/usr/lib" "$PROOT_DIR/usr/libexec/proot"
PACKAGES_GZ="$WORK_DIR/Packages.gz"
curl -fsSL --retry 3 -o "$PACKAGES_GZ" "$TERMUX_MIRROR/dists/stable/main/binary-aarch64/Packages.gz"
gunzip -c "$PACKAGES_GZ" > "$WORK_DIR/Packages"

fetch_deb() {
  local pkg="$1" filename deb
  filename="$(awk -v p="$pkg" '
    $0 == "Package: " p {found=1}
    found && $1 == "Filename:" {print $2; exit}
  ' "$WORK_DIR/Packages")"
  [ -n "$filename" ] || { echo "package not found: $pkg" >&2; exit 1; }
  deb="$WORK_DIR/$(basename "$filename")"
  curl -fsSL --retry 3 -o "$deb" "$TERMUX_MIRROR/$filename"
  printf '%s\n' "$deb"
}

for pkg in proot libtalloc libandroid-shmem libandroid-support; do
  deb="$(fetch_deb "$pkg")"
  log "extract $pkg"
  rm -rf "$WORK_DIR/deb"; mkdir -p "$WORK_DIR/deb"
  (cd "$WORK_DIR/deb" && ar x "$deb")
  data="$(find "$WORK_DIR/deb" -name 'data.tar.*' -print -quit)"
  tar -xf "$data" -C "$WORK_DIR/deb"
  src="$WORK_DIR/deb/data/data/com.termux/files/usr"
  [ -d "$src" ] || { echo "unexpected layout in $deb" >&2; exit 1; }
  cp -a "$src/." "$PROOT_DIR/usr/"
done

log "patch PRoot prefix -> /data/data/$APP_PACKAGE/files/usr"
bash "$SCRIPT_DIR/patch-proot-prefix.sh" "$PROOT_DIR/usr" "$APP_PACKAGE"

# Fail loudly if the rewrite missed a binary: PRoot would abort at exec time.
if grep -rqF -- "/data/data/com.termux/files/usr" "$PROOT_DIR/usr" 2>/dev/null; then
  echo "Termux prefix still present after patching" >&2
  grep -rlF -- "/data/data/com.termux/files/usr" "$PROOT_DIR/usr" >&2 || true
  exit 1
fi

# Archive with the same relative layout the app extracts into `files/`, so the
# payload mirrors the on-device tree instead of relying on a hardcoded prefix.
log "stage payload layout"
PAYLOAD_DIR="$WORK_DIR/payload"
rm -rf "$PAYLOAD_DIR"
mkdir -p "$PAYLOAD_DIR"
cp -a "$PROOT_DIR/usr" "$PAYLOAD_DIR/usr"

log "package payloads"
sudo tar -C "$ROOTFS" --numeric-owner --owner=0 --group=0 -cf - . | gzip -9 > "$OUT_DIR/rootfs.tar.gz"
tar -C "$PAYLOAD_DIR" --numeric-owner --owner=0 --group=0 -cf - . | gzip -9 > "$OUT_DIR/proot.tar.gz"

log "manifest"
ROOTFS_BYTES="$(stat -c%s "$OUT_DIR/rootfs.tar.gz")"
PROOT_BYTES="$(stat -c%s "$OUT_DIR/proot.tar.gz")"
ROOTFS_SHA="$(sha256sum "$OUT_DIR/rootfs.tar.gz" | cut -d' ' -f1)"
PROOT_SHA="$(sha256sum "$OUT_DIR/proot.tar.gz" | cut -d' ' -f1)"

ROOTFS_BYTES="$ROOTFS_BYTES" PROOT_BYTES="$PROOT_BYTES" \
ROOTFS_SHA="$ROOTFS_SHA" PROOT_SHA="$PROOT_SHA" \
UBUNTU_VERSION="$UBUNTU_VERSION" NODE_MAJOR="$NODE_MAJOR" NVM_VERSION="$NVM_VERSION" \
PNPM_VERSION="$PNPM_VERSION" DSH_VERSION="$DSH_VERSION" APP_PACKAGE="$APP_PACKAGE" \
python3 - "$OUT_DIR/runtime-manifest.json" <<'PY'
import json, os, sys

def env(name, default=""):
    return os.environ.get(name, default)

manifest = {
    "schema": 1,
    "ubuntu": env("UBUNTU_VERSION"),
    "arch": "arm64",
    "appPackage": env("APP_PACKAGE"),
    "nodeMajor": env("NODE_MAJOR"),
    "nvm": env("NVM_VERSION"),
    "pnpm": env("PNPM_VERSION"),
    "dsh": env("DSH_VERSION"),
    "proot": "5.1.107.102",
    "rootfs": {
        "file": "rootfs.tar.gz",
        "bytes": int(env("ROOTFS_BYTES", "0")),
        "sha256": env("ROOTFS_SHA"),
    },
    "prootPayload": {
        "file": "proot.tar.gz",
        "bytes": int(env("PROOT_BYTES", "0")),
        "sha256": env("PROOT_SHA"),
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY

log "done"
ls -lh "$OUT_DIR"
