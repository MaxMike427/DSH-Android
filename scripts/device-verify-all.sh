#!/system/bin/sh
# Full on-device verification of the shipped runtime.
#
# Mirrors what the app does (same uid, same PRoot argv, same profile patch) so a
# pass here means the in-app path works. Intended to run after the app has
# completed its own extraction.
PKG=com.dshapp
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "=== 1. payload present"
run-as $PKG sh -c 'test -f files/usr/bin/proot' || fail "proot binary missing (extraction did not complete)"
run-as $PKG sh -c 'test -f files/rootfs/bin/bash' || fail "rootfs missing"
run-as $PKG sh -c 'test -f files/rootfs/root/.dsh/profiles/web/cordis.patch.yml' || fail "profile patch missing"
echo "ok"

echo
echo "=== 2. guest toolchain"
OUT=$(run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "
      . /etc/os-release; echo OS=\$PRETTY_NAME
      . /root/.nvm/nvm.sh 2>/dev/null
      echo NODE=\$(node --version 2>/dev/null)
      echo PNPM=\$(pnpm --version 2>/dev/null)
      echo DSH=\$(dsh --version 2>/dev/null)
      echo GIT=\$(git --version 2>/dev/null)
    "
' 2>&1)
echo "$OUT"
echo "$OUT" | grep -q "NODE=v" || fail "node did not run in the guest"
echo "$OUT" | grep -q "DSH=" || fail "dsh did not run in the guest"

echo
echo "=== 3. dsh web boots and serves"
OUT=$(run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "
      . /root/.nvm/nvm.sh
      cd /root
      dsh web --no-open --port 3080 > /tmp/verify.log 2>&1 &
      pid=\$!
      for i in \$(seq 1 60); do
        code=\$(node -e \"require(String.fromCharCode(104,116,116,112)).get(String.fromCharCode(104,116,116,112,58,47,47,49,50,55,46,48,46,48,46,49,58,51,48,56,48,47),r=>{console.log(r.statusCode);process.exit(0)}).on(String.fromCharCode(101,114,114,111,114),()=>process.exit(1))\" 2>/dev/null)
        if [ -n \"\$code\" ]; then echo \"HTTP=\$code\"; break; fi
        sleep 2
      done
      grep -i \"did not activate\\|Error:\" /tmp/verify.log | head -5
      kill \$pid 2>/dev/null
    "
' 2>&1)
echo "$OUT" | tail -8
echo "$OUT" | grep -q "HTTP=401\|HTTP=200" || fail "dsh web did not serve"
if echo "$OUT" | grep -qi "did not activate"; then
  fail "a plugin failed to activate (see the warning above)"
fi

echo
echo "=== ALL CHECKS PASSED"
