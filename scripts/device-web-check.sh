#!/system/bin/sh
# Start dsh web, then fetch the UI from inside the guest to prove it serves.
PKG=com.dshapp

run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys \
    -b $PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "
      . /root/.nvm/nvm.sh
      cd /root
      dsh web --no-open --port 3080 > /tmp/web.log 2>&1 &
      pid=\$!
      for i in \$(seq 1 60); do
        if node -e \"require(\\\"http\\\").get(\\\"http://127.0.0.1:3080/\\\",r=>{console.log(\\\"HTTP\\\",r.statusCode);process.exit(0)}).on(\\\"error\\\",()=>process.exit(1))\" 2>/dev/null; then
          echo SERVER_OK
          break
        fi
        sleep 2
      done
      echo --- log ---
      tail -5 /tmp/web.log
      kill \$pid 2>/dev/null
    "
' 2>&1 | tail -12
