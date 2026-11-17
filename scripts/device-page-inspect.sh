#!/system/bin/sh
# Ask the running page for its real layout metrics.
#
# The screen showed only the page background with ~8px of text, so the question
# is whether the document is sized correctly and merely invisible, or laid out
# into a tiny box. This reads the numbers from inside the WebView.
PKG=com.dshapp
PORT=3080
TOKEN=$(run-as $PKG sh -c "tail -1 files/logs/dsh-web.log" | sed -n 's#.*token=\([A-Za-z0-9_-]*\).*#\1#p')

run-as $PKG sh -c "
  cd files || exit 1
  export PROOT_LOADER=\$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=\$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r \$PWD/rootfs \
    -b /dev -b /proc -b /sys -b \$PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc \"
      echo === html head
      curl -s 'http://127.0.0.1:$PORT/?token=$TOKEN' -L | head -c 600
      echo
      echo === css files referenced
      curl -s 'http://127.0.0.1:$PORT/?token=$TOKEN' -L | grep -o '[^\\\"]*\\.css' | head -5
    \"
" 2>&1 | tail -25
