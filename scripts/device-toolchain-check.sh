#!/system/bin/sh
# Run the guest toolchain exactly as the app does (no /root override).
PKG=com.dshapp

run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot \
    --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys \
    -b $PWD/tmp:/tmp \
    -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=dumb \
    /bin/bash -lc "
      . /etc/os-release; echo OS=\$PRETTY_NAME
      echo ARCH=\$(uname -m)
      . /root/.nvm/nvm.sh 2>/dev/null
      echo NODE=\$(node --version 2>/dev/null)
      echo NPM=\$(npm --version 2>/dev/null)
      echo PNPM=\$(pnpm --version 2>/dev/null)
      echo DSH=\$(dsh --version 2>/dev/null)
      echo GIT=\$(git --version 2>/dev/null)
      test -f /root/.dsh/profiles/web/cordis.patch.yml && echo PATCH=present || echo PATCH=MISSING
    "
' 2>&1 | tail -20
