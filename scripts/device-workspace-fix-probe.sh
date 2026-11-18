#!/system/bin/sh
# Verify the fix for "cannot create the default workspace" without rebuilding:
#   1. create the visible Documents tree inside the guest rootfs
#   2. patch the web profile so the controller is given an explicit
#      documentsDirectory (it then never calls the missing xdg-user-dir)
#   3. restart the app and see whether the harness adopts a default workspace
#
# Reverting: delete the two lines from cordis.patch.yml and remove /root/Documents.
PKG=com.dshapp
ROOTFS=files/rootfs

# Step 1: the directory the harness will adopt. Visible, so the workspace picker
# also lists something a user can tap.
run-as $PKG sh -c "mkdir -p $ROOTFS/root/Documents/deepseek-harness/default-workspace && chmod 0755 $ROOTFS/root/Documents"

# Step 2: give the controller an explicit Documents directory.
cat > /data/local/tmp/patch-section.yml <<'YML'
# The harness creates a first-use workspace under the account's Documents
# directory, which it discovers by running `xdg-user-dir DOCUMENTS`. A PRoot
# guest has no xdg-user-dirs, so that lookup fails and no default workspace is
# ever created. Supplying the directory directly skips the native probe.
- id: workspace-controller
  config:
    documentsDirectory: /root/Documents
YML

run-as $PKG sh -c '
  cd files/rootfs/root/.dsh/profiles/web || exit 1
  cp cordis.patch.yml cordis.patch.yml.bak
  cat /data/local/tmp/patch-section.yml >> cordis.patch.yml
  chmod 0644 cordis.patch.yml
  echo "--- patched profile tail"
  tail -8 cordis.patch.yml
'

echo "=== restarting the app"
am force-stop $PKG
am start -n com.dshapp/com.dsh.android.MainActivity >/dev/null
