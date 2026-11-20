#!/system/bin/sh
# Check what dsh-pet serves on its own HTTP prefix: is there a page the overlay
# could load, or only API/asset endpoints?
PKG=com.dshapp
TOKEN=$(run-as $PKG sh -c "tail -1 files/logs/dsh-web.log" | sed -n 's#.*token=\([A-Za-z0-9_-]*\).*#\1#p')

cat > /data/local/tmp/guest-pet.sh <<'GUEST'
set -e
. /root/.nvm/nvm.sh
cd /root
token="$1"
base="http://127.0.0.1:3080"

echo "=== obtain the auth cookie"
cookie=$(curl -sS -D - -o /dev/null "$base/?token=$token" | sed -n 's/^[Ss]et-[Cc]ookie: *\([^;]*\).*/\1/p' | head -1)
echo "  cookie: ${cookie:0:24}..."

probe() {
  printf '\n=== %s\n' "$1"
  code=$(curl -sS -o /tmp/pet.out -w '%{http_code}' -H "cookie: $cookie" "$base$1")
  printf '  status: %s  bytes: %s  type: %s\n' "$code" "$(wc -c </tmp/pet.out)" "$(file -b /tmp/pet.out 2>/dev/null | cut -c1-40)"
  head -c 300 /tmp/pet.out | tr -d '\000' | head -6
}

probe /dsh-pet-7340
probe /dsh-pet-7340/
probe /dsh-pet-7340/state
probe /dsh-pet-7340/config
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-pet.sh > files/rootfs/root/guest-pet.sh'
run-as $PKG sh -c "
  cd files || exit 1
  export PROOT_LOADER=\$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=\$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r \$PWD/rootfs -b /dev -b /proc -b /sys -b \$PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/root/.nvm/versions/node/v24.22.3/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash /root/guest-pet.sh '$TOKEN'
" 2>&1 | tail -40