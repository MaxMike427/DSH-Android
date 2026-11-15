#!/usr/bin/env bash
# Rewrite the hardcoded Termux prefix inside PRoot's ELF binaries.
#
# The Termux build of PRoot has DT_RUNPATH and PROOT_UNBUNDLE_LOADER pointing at
# /data/data/com.termux/files/usr. An app that keeps its own package name cannot
# use them as-is. Because the replacement string is the same length, the bytes
# can be swapped in place without touching ELF offsets or section sizes.
#
# The app's package name is therefore fixed to a 10-character value
# ("com.dshapp") so it matches "com.termux" exactly.
set -euo pipefail

PREFIX_DIR="${1:?usage: patch-proot-prefix.sh <extracted-proot-usr-dir> [package]}"
PACKAGE="${2:-com.dshapp}"

OLD="/data/data/com.termux/files/usr"
NEW="/data/data/$PACKAGE/files/usr"

if [ "${#OLD}" -ne "${#NEW}" ]; then
  echo "prefix length mismatch: $OLD (${#OLD}) vs $NEW (${#NEW})" >&2
  exit 1
fi

patched=0
while IFS= read -r -d '' file; do
  if grep -qF -- "$OLD" "$file" 2>/dev/null; then
    # perl keeps the replacement byte-exact and avoids sed delimiter issues.
    OLD="$OLD" NEW="$NEW" perl -0777 -pi -e '
      my $old = $ENV{OLD};
      my $new = $ENV{NEW};
      s/\Q$old\E/$new/g;
    ' "$file"
    patched=$((patched + 1))
    echo "patched: ${file#$PREFIX_DIR/}"
  fi
done < <(find "$PREFIX_DIR" -type f -print0)

if [ "$patched" -eq 0 ]; then
  echo "no binaries contained the Termux prefix; nothing to patch" >&2
fi

echo "patched $patched file(s) to $NEW"

# Deduplicate the talloc library copies the Termux package ships as separate
# regular files (symlinks do not survive every extraction path).

