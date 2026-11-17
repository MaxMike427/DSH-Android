#!/system/bin/sh
# Run the guest-side smoke assertions from scripts/smoke-rootfs.sh on a real
# device, inside PRoot.
#
# CI composes and boots the same tree through chroot, so this probe is the
# device-truth check: it catches differences the chroot host cannot show, such as
# plugin activation failures and API routes that only the real boot reaches.
PKG=com.dshapp
HERE=$(dirname "$0")

cat > /data/local/tmp/guest-smoke.sh <<'GUEST'
set -e
. /root/.nvm/nvm.sh
cd /root

echo "=== composed bash row"
dsh --profile web --dump-config >/tmp/composed.yml 2>/tmp/composed.err || true
grep -n -A3 "^- id: bash$" /tmp/composed.yml || true
echo "=== composed sandbox row"
grep -n -A4 "^- id: bash-sandbox$" /tmp/composed.yml || true
echo "=== patch diagnostics"
cat /tmp/composed.err || true

if ! grep -q profile-shim.js /tmp/composed.yml; then
  echo "FAIL: the shim did not compose into the bash row"
  exit 1
fi
if ! awk '
  /^- id: bash-sandbox$/ { inside = 1; next }
  inside && /^- / { inside = 0 }
  inside && /^  disabled: true$/ { found = 1 }
  END { exit found ? 0 : 1 }
' /tmp/composed.yml; then
  echo "FAIL: bash-sandbox is still enabled"
  exit 1
fi
echo "ok: composed tree matches the smoke assertions"

echo "=== web boot + catalog probe"
dsh web --no-open --port 3098 >/tmp/web.log 2>&1 &
pid=$!
ready=0
for _ in $(seq 1 180); do
  if (exec 3<>/dev/tcp/127.0.0.1/3098) 2>/dev/null; then ready=1; break; fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "FAIL: web exited early"
    cat /tmp/web.log
    exit 1
  fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo "FAIL: web did not become ready"
  cat /tmp/web.log
  exit 1
fi
echo "web: listening on 127.0.0.1:3098"

token=""
for _ in $(seq 1 30); do
  token=$(sed -n 's#.*token=\([A-Za-z0-9_-]*\).*#\1#p' /tmp/web.log | tail -1)
  [ -n "$token" ] && break
  sleep 1
done
if [ -z "$token" ]; then
  echo "FAIL: no auth token in the web log"
  cat /tmp/web.log
  exit 1
fi
echo "token length: ${#token}"

node -e '
  const base = "http://127.0.0.1:3098"
  const token = process.argv[1]
  async function main() {
    const first = await fetch(base + "/?token=" + token, { redirect: "manual" })
    const setCookie = first.headers.get("set-cookie") || ""
    const cookie = setCookie.split(";")[0]
    const catalog = await fetch(base + "/api/permissionPresets/catalog", {
      headers: cookie ? { cookie } : {},
      redirect: "manual",
    })
    const body = await catalog.text()
    console.log("catalog: " + catalog.status + " body=" + body.slice(0, 120))
    if (catalog.status === 404) process.exit(1)
  }
  main().catch(err => { console.log("probe error: " + err.message); process.exit(1) })
' "$token"
echo "catalog probe passed"

kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true

echo "=== activation warnings"
grep -c "did not activate" /tmp/web.log || true
grep -c "does not confine" /tmp/web.log || true
tail -3 /tmp/web.log
GUEST

run-as $PKG sh -c 'cat /data/local/tmp/guest-smoke.sh > files/rootfs/root/guest-smoke.sh && chmod 0755 files/rootfs/root/guest-smoke.sh'
if [ $? -ne 0 ]; then
  echo "FAIL: could not stage the guest script"
  exit 1
fi

run-as $PKG sh -c '
  cd files || exit 1
  export PROOT_LOADER=$PWD/usr/libexec/proot/loader
  export PROOT_TMP_DIR=$PWD/tmp
  export PROOT_NO_SECCOMP=1
  exec usr/bin/proot --kill-on-exit --link2symlink -0 \
    -r $PWD/rootfs -b /dev -b /proc -b /sys -b $PWD/tmp:/tmp -w /root \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash /root/guest-smoke.sh
' 2>&1 | tail -40
