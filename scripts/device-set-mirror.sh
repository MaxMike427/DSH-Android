#!/system/bin/sh
# Point the guest's apt at the Tsinghua mirror and refresh its index.
#
# Ubuntu Base ships ports.ubuntu.com, which is slow from mainland China. The
# mirror list is written by the app at install time (see DshRuntime) so a fresh
# extraction already has it; this script applies it to an existing install.
PKG=com.dshapp
GUEST_SUDO=""

run-as $PKG sh -c 'cat > files/rootfs/etc/apt/sources.list' <<'SOURCES'
# Tsinghua University mirror (TUNA) - arm64/ports
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-backports main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-security main restricted universe multiverse
SOURCES

echo "=== sources.list written"
run-as $PKG sh -c 'cat files/rootfs/etc/apt/sources.list'
