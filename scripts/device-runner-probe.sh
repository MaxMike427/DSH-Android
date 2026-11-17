#!/system/bin/sh
# Prove PRoot accepts a `-- command` invocation with an explicit argv, which is
# what a sandbox runner must do: no shell string, just the command to confine.
PKG=com.dshapp

run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs \
    -b /dev -b /proc -b /sys \
    -w /root \
    -- /bin/sh -c "echo RUNNER_OK; echo uid=\$(id -u); pwd"
' 2>&1 | tail -6
