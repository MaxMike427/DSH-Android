#!/system/bin/sh
# Print the module's export names, using a probe file written inside the guest.
PKG=com.dshapp

cat > /data/local/tmp/probe.mjs <<'JS'
const base = '/root/.nvm/versions/node/v24.22.3/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-bash-local/lib/index.js'
const mod = await import('file://' + base)
console.log('exports:', Object.keys(mod).join(','))
console.log('default is class:', typeof mod.default)
if (typeof mod.default === 'function') {
  console.log('proto has sandboxMode:', 'sandboxMode' in mod.default.prototype)
}
JS

run-as $PKG sh -c "
  cd files || exit 1
  cat /data/local/tmp/probe.mjs > /root/probe.mjs 2>/dev/null || true
  export PROOT_LOADER=\$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=\$PWD/tmp
  export PROOT_NO_SECCOMP=1
  cp /data/local/tmp/probe.mjs \$PWD/rootfs/root/probe.mjs
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r \$PWD/rootfs -b /dev -b /proc -b /sys -b \$PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/root/.nvm/versions/node/v24.22.3/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash -lc 'node /root/probe.mjs'
" 2>&1 | tail -8
