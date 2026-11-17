#!/system/bin/sh
# Determine how `dsh web` authenticates a browser session.
#
# If a cookie is issued after the tokenized request, the UI can be opened by a
# plain URL afterwards (which is what system browsers keep when the query string
# is stripped). If it is header-only, the token must stay in the URL.
PKG=com.dshapp
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
      T=$TOKEN
      echo === response headers for tokenized GET
      curl -s -D - -o /dev/null \\\"http://127.0.0.1:3080/?token=\\\$T\\\" | head -12
      echo
      echo === plain GET without token
      curl -s -o /dev/null -w 'status=%{http_code}\\\\n' http://127.0.0.1:3080/
    \"
" 2>&1 | tail -22
