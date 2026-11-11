#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="${MAPLIBRE_MAVEN_ROOT:-$script_dir/../.maplibre-maven}"
download_dir="$(mktemp -d)"

cleanup() {
  rm -rf "$download_dir"
}
trap cleanup EXIT

download_repository() {
  local name="$1"
  local url="$2"
  local expected_sha256="$3"
  local destination="$4"
  local archive="$download_dir/$name.zip"

  curl --fail --location --retry 3 --silent --show-error --output "$archive" "$url"
  printf '%s  %s\n' "$expected_sha256" "$archive" | shasum -a 256 --check --status
  mkdir -p "$destination"
  unzip -oq "$archive" -d "$destination"
}

download_repository \
  "maplibre-native-ffi" \
  "https://github.com/hyoban/maplibre-native-ffi/releases/download/here-android-v0.202611.0-hyoban.1/maplibre-native-ffi-maven-0.202611.0-hyoban.1.zip" \
  "c422f9f55cc0e3904ba9184d68ace1ee8be4192a68034308c432a5a0913ac285" \
  "$repository_root/native-ffi"

download_repository \
  "maplibre-compose" \
  "https://github.com/hyoban/maplibre-compose/releases/download/here-android-v0.21.0-hyoban.1/maplibre-compose-maven-0.21.0-hyoban.1.zip" \
  "5b08374375efe4873840203f2db52ebc47dbbe23eb87aa937a03702d945c7132" \
  "$repository_root/compose"

printf 'MapLibre Maven repositories are ready in %s\n' "$repository_root"
