#!/system/bin/sh
# Probe whether the app may execute binaries from its private storage.
# Android 10+ blocks this for targetSdk >= 29, which is why the app ships
# targetSdk 28. Prints EXEC_OK on success.
set -e
cd "$(dirname "$0")"
printf '#!/system/bin/sh\necho EXEC_OK\n' > probe.sh
chmod 755 probe.sh
./probe.sh
