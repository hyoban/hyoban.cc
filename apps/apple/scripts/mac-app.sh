#!/bin/sh
# Builds the native Mac app and wraps it into Here.app under apps/apple/build:
#   scripts/mac-app.sh            a debug build
#   scripts/mac-app.sh --release  an optimized one
# Who the app belongs to (its id, the API and where media are served) comes from the
# repository's site.config.json; the icon is drawn from the Compose app's here.svg.
set -eu

cd "$(dirname "$0")/.."
root=$(cd ../.. && pwd)
configuration=debug
[ "${1:-}" = "--release" ] && configuration=release

swift build -c "$configuration" --product HereMac
products=$(swift build -c "$configuration" --show-bin-path)

app=build/Here.app
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$products/HereMac" "$app/Contents/MacOS/Here"
cp -R "$products/HereKit_HereMac.bundle" "$app/Contents/Resources/"

# The cities the map's fog lifts, as the site and Android ship them.
cp "$root/apps/web/src/data/boundaries/cities.json" "$app/Contents/Resources/cities.json"

# Where the basemap is drawn in GCJ-02, so what the map draws there is shifted onto it.
cp "$root/apps/android/app/src/main/resources/gcj-area.json" "$app/Contents/Resources/gcj-area.json"

icon=build/Here.icns
if [ ! -f "$icon" ] || [ "$root/apps/android/desktop/icon/here.svg" -nt "$icon" ]; then
  (cd "$root/apps/android/desktop/icon" && swift icns.swift here.svg "$root/apps/apple/$icon")
fi
cp "$icon" "$app/Contents/Resources/Here.icns"

setting() { plutil -extract "$1" raw -o - "$root/site.config.json"; }
application_id=$(setting android.applicationId)
version=$(git -C "$root" describe --tags --match 'mac-v*' --abbrev=0 2>/dev/null | sed 's/^mac-v//' || true)
[ -n "$version" ] || version=0.1.0

cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>Here</string>
  <key>CFBundleIdentifier</key><string>$application_id.mac</string>
  <key>CFBundleName</key><string>Here</string>
  <key>CFBundleDisplayName</key><string>Here</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleIconFile</key><string>Here</string>
  <key>CFBundleShortVersionString</key><string>$version</string>
  <key>CFBundleVersion</key><string>$version</string>
  <key>CFBundleDevelopmentRegion</key><string>zh-Hans</string>
  <key>CFBundleLocalizations</key><array><string>zh-Hans</string><string>en</string></array>
  <key>LSApplicationCategoryType</key><string>public.app-category.lifestyle</string>
  <key>LSMinimumSystemVersion</key><string>26.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocationWhenInUseUsageDescription</key><string>Find places near you.</string>
  <key>HereDataFolder</key><string>HereMac</string>
  <key>HereAPIURL</key><string>$(setting api.url)</string>
  <key>HereAssetsOrigin</key><string>$(setting assets.origin)</string>
  <key>HereSiteURL</key><string>$(setting site.url)</string>
</dict>
</plist>
PLIST

codesign --force --sign - "$app" >/dev/null
echo "$PWD/$app"
