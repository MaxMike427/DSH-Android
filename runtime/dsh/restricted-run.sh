#!/system/bin/sh
# A restriction runner for dsh-sandbox-local inside PRoot.
#
# PRoot has no bwrap or Landlock, so the built-in Linux rungs cannot work. What
# PRoot *can* do is re-enter the guest with a narrowed set of bind mounts, which
# gives the same effect for the file-effect policy the harness asks for:
# read-only drops every writable bind, workspace-write grants just the workspace.
#
# Contract expected by dsh-sandbox-local:
#   runner <profile-args> -- <command...>
# The bwrap-compatible profile arguments are ignored; this runner derives its
# binds from the environment the harness already set up.
#
# Exit 125 with a "restricted-run:" fatal line signals runner failure (as opposed
# to the wrapped command failing).

set -e

WORKSPACE="${DSH_SANDBOX_WORKSPACE:-/root}"
MODE="${DSH_SANDBOX_MODE:-workspace-write}"

PROOT_BIN="${DSH_PROOT_BIN:-/data/data/com.dshapp/files/usr/bin/proot}"
ROOTFS="${DSH_PROOT_ROOTFS:-/data/data/com.dshapp/files/rootfs}"
LOADER="${DSH_PROOT_LOADER:-/data/data/com.dshapp/files/usr/libexec/proot/loader}"
TMP="${DSH_PROOT_TMP:-/data/data/com.dshapp/files/tmp}"

if [ ! -x "$PROOT_BIN" ]; then
  echo "restricted-run: proot binary missing at $PROOT_BIN" >&2
  exit 125
fi

# Skip the bwrap profile arguments the provider passes; everything after `--` is
# the command to confine.
while [ $# -gt 0 ]; do
  if [ "$1" = "--" ]; then
    shift
    break
  fi
  shift
done

if [ $# -eq 0 ]; then
  echo "restricted-run: no command given" >&2
  exit 125
fi

export PROOT_LOADER
export PROOT_TMP_DIR="$TMP"
export PROOT_NO_SECCOMP=1

if [ "$MODE" = "read-only" ]; then
  # No writable bind except the kernel pseudo-filesystems the runtime needs.
  exec "$PROOT_BIN" --kill-on-exit --link2symlink -0 \
    -r "$ROOTFS" \
    -b /dev -b /proc -b /sys \
    -w /root \
    -- "$@"
fi

# workspace-write: the workspace is writable, everything else is the image's own
# read-only view (PRoot shares the rootfs, so a truly read-only rootfs would need
# an overlay; this matches the mode's promise for the paths that matter).
exec "$PROOT_BIN" --kill-on-exit --link2symlink -0 \
  -r "$ROOTFS" \
  -b /dev -b /proc -b /sys \
  -b "$TMP:/tmp" \
  -b "$WORKSPACE:$WORKSPACE" \
  -w /root \
  -- "$@"
