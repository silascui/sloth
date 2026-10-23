#!/usr/bin/env bash
set -euo pipefail

# Generate neutral sample assets without recording an app or using external media.
# Requires ffmpeg with libx264. Existing demo assets are intentionally replaced.
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets_dir="$root_dir/sample/src/main/assets"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

for index in 1 2; do
  hue=$(( (index - 1) * 120 ))
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i 'testsrc2=size=1080x1920:rate=24:duration=3' \
    -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' \
    -map 0:v:0 -map 1:a:0 -t 3 -vf "hue=h=$hue" \
    -c:v libx264 -preset medium -crf 23 -profile:v high -pix_fmt yuv420p \
    -maxrate 5M -bufsize 10M -tag:v avc1 \
    -c:a aac -b:a 64k -map_metadata -1 \
    -brand isom -movflags +faststart "$work_dir/demo$index.mp4"
done

# Keep the playback fixture compatible with API 28 software decoding and ensure
# precise seeking at two seconds still requires decoding from the first keyframe.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=360x640:rate=24:duration=3' \
  -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' \
  -map 0:v:0 -map 1:a:0 -t 3 \
  -c:v libx264 -preset medium -crf 23 -profile:v baseline -level:v 3.0 \
  -pix_fmt yuv420p -g 240 -keyint_min 240 -sc_threshold 0 \
  -c:a aac -b:a 64k -map_metadata -1 \
  -brand isom -movflags +faststart "$work_dir/smoke.mp4"

mkdir -p "$assets_dir"
for index in 1 2; do
  cp "$work_dir/demo$index.mp4" "$assets_dir/demo$index.mp4"
done
cp "$work_dir/smoke.mp4" "$root_dir/player/src/androidTest/assets/smoke.mp4"
