#!/usr/bin/env sh
# Render brand PNGs from the SVG sources (requires rsvg-convert from librsvg).
set -eu
cd "$(dirname "$0")/.."
B=custom_components/blueshark/brand
rsvg-convert -w 256 -h 256 branding/icon.svg -o "$B/icon.png"
cp "$B/icon.png" "$B/dark_icon.png"
rsvg-convert -w 600 -h 128 branding/logo.svg -o "$B/logo.png"
rsvg-convert -w 600 -h 128 branding/logo-dark.svg -o "$B/dark_logo.png"
rsvg-convert -w 256 -h 256 branding/icon.svg -o addon/icon.png
cp "$B/logo.png" addon/logo.png
rsvg-convert -w 1024 -h 1024 branding/icon.svg -o branding/icon-1024.png
rsvg-convert -w 1200 -h 256 branding/logo.svg -o branding/logo-1200.png
cp branding/icon.svg dist/icon.svg
