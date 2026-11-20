#!/system/bin/sh
# Apply the same dsh-at-file patch that scripts/build-rootfs.sh applies at image
# build time, so the device proves the patch is what fixes the activation.
PKG=com.dshapp

cat > /data/local/tmp/guest-patch.sh <<'GUEST'
set -e
at_file=/root/.dsh/profiles/web/node_modules/dsh-at-file/lib/index.js
if [ ! -f "$at_file" ]; then echo "dsh-at-file not installed"; exit 1; fi
python3 - "$at_file" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
import_line = 'import { settingsNamespace } from "@deepseek-ai/dsh-settings";'
if import_line in text:
    # Write through a sibling and replace: the installed file is a pnpm hard
    # link, and rewriting it in place would also rewrite the store entry.
    staged = path.with_suffix(".patched")
    staged.write_text(
        text.replace(import_line, "").replace('settingsNamespace("at-file")', '"at-file"'),
        encoding="utf-8",
    )
    staged.replace(path)
    print("patched")
else:
    print("already patched or import absent")
PY
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-patch.sh > files/rootfs/root/guest-patch.sh'
run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash /root/guest-patch.sh
' 2>&1 | tail -5
