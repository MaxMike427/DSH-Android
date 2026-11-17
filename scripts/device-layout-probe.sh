#!/system/bin/sh
# Diagnose the collapsed layout by asking the page for its real box sizes.
#
# Screenshots show every painted pixel inside a 10px band at the screen bottom,
# which means the document reports a tiny height. These numbers say whether the
# cause is the viewport WebView reports or the app shell's own CSS.
PKG=com.dshapp
TOKEN=$(run-as $PKG sh -c "tail -1 files/logs/dsh-web.log" | sed -n 's#.*token=\([A-Za-z0-9_-]*\).*#\1#p')

run-as $PKG sh -c "
  cd files || exit 1
  export PROOT_LOADER=\$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=\$PWD/tmp
  export PROOT_NO_SECCOMP=1
  cat > \$PWD/rootfs/root/probe.mjs <<'JS'
const base = 'http://127.0.0.1:3080'
const token = process.env.T
const r = await fetch(base + '/?token=' + token, { redirect: 'manual' })
const cookie = r.headers.get('set-cookie')
console.log('tokenized status:', r.status)
const html = await (await fetch(base + '/', { headers: cookie ? { cookie: cookie.split(';')[0] } : {} })).text()
console.log('html bytes:', html.length)
const m = html.match(/<meta[^>]*viewport[^>]*>/)
console.log('viewport meta:', m ? m[0] : 'none')
const css = html.match(/href=\"([^\"]*\\.css)\"/g)
console.log('css refs:', css)
JS
  cp /data/local/tmp/probe.mjs \$PWD/rootfs/root/probe.mjs 2>/dev/null || true
  T=\$TOKEN exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r \$PWD/rootfs -b /dev -b /proc -b /sys -b \$PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root T=$TOKEN PATH=/root/.nvm/versions/node/v24.22.3/bin:/usr/bin:/bin \
    /bin/bash -lc 'node /root/probe.mjs'
" 2>&1 | tail -12
