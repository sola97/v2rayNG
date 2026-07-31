#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <apk>" >&2
  exit 2
fi

apk="$1"
if [[ ! -s "$apk" ]]; then
  echo "APK does not exist or is empty: $apk" >&2
  exit 3
fi

entries="$(unzip -Z1 "$apk")"
mapfile -t abis < <(
  sed -n 's#^lib/\([^/][^/]*\)/libgojni\.so$#\1#p' <<<"$entries" | sort -u
)

if [[ ${#abis[@]} -eq 0 ]]; then
  echo "APK does not contain libgojni.so for any ABI: $apk" >&2
  exit 4
fi

required_libraries=(
  libgojni.so
  libhev-socks5-tunnel.so
  libhevsockstun.so
)

for abi in "${abis[@]}"; do
  for library in "${required_libraries[@]}"; do
    entry="lib/${abi}/${library}"
    if ! grep -Fqx "$entry" <<<"$entries"; then
      echo "APK is missing required native file: $entry ($apk)" >&2
      exit 5
    fi
  done
done

echo "Verified native APK libraries for ABI(s): ${abis[*]} ($apk)"
