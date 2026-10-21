#!/usr/bin/env bash
# Generates small synthetic qualification clips. Large production videos never belong in the repository.
#
#   sync_360_mono.mp4  2048x1024 equirect 360, frame counter, white flash + 1 kHz beep every second.
#                      Use with a high-speed camera / audio capture to measure start skew.
#   eyes_360_tb.mp4    2048x2048 top/bottom stereo with LEFT on top, RIGHT below, for eye-order checks.
#   eyes_flat_sbs.mp4  3840x1080 side-by-side flat stereo, LEFT | RIGHT.
#
# Requires ffmpeg. Output goes to test-fixtures/out/ (git-ignored).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
DUR="${DUR:-20}"

ffmpeg -y -loglevel error \
  -f lavfi -i "color=c=0x101828:s=2048x1024:r=30:d=${DUR}" \
  -f lavfi -i "sine=frequency=1000:sample_rate=48000:duration=${DUR}" \
  -filter_complex "\
[0:v]drawbox=x=924:y=412:w=200:h=200:color=white:t=fill:enable='lt(mod(t\,1)\,0.1)',\
drawtext=text='%{frame_num}':start_number=0:fontcolor=white:fontsize=160:x=(w-tw)/2:y=h*0.68,\
drawtext=text='t=%{pts\:hms}':fontcolor=0x9AA3B5:fontsize=56:x=(w-tw)/2:y=h*0.2[v];\
[1:a]volume='if(lt(mod(t\,1)\,0.1)\,1\,0)':eval=frame[a]" \
  -map "[v]" -map "[a]" -c:v libx264 -profile:v high -pix_fmt yuv420p -g 30 -c:a aac -b:a 128k \
  -metadata:s:v stereo_mode=mono out/sync_360_mono.mp4

ffmpeg -y -loglevel error \
  -f lavfi -i "color=c=0x1d4ed8:s=2048x1024:r=30:d=10" -f lavfi -i "color=c=0xb91c1c:s=2048x1024:r=30:d=10" \
  -filter_complex "\
[0:v]drawtext=text='LEFT EYE':fontcolor=white:fontsize=180:x=(w-tw)/2:y=(h-th)/2[l];\
[1:v]drawtext=text='RIGHT EYE':fontcolor=white:fontsize=180:x=(w-tw)/2:y=(h-th)/2[r];\
[l][r]vstack[v]" -map "[v]" -c:v libx264 -pix_fmt yuv420p out/eyes_360_tb.mp4

ffmpeg -y -loglevel error \
  -f lavfi -i "color=c=0x1d4ed8:s=1920x1080:r=30:d=10" -f lavfi -i "color=c=0xb91c1c:s=1920x1080:r=30:d=10" \
  -filter_complex "\
[0:v]drawtext=text='LEFT':fontcolor=white:fontsize=200:x=(w-tw)/2:y=(h-th)/2[l];\
[1:v]drawtext=text='RIGHT':fontcolor=white:fontsize=200:x=(w-tw)/2:y=(h-th)/2[r];\
[l][r]hstack[v]" -map "[v]" -c:v libx264 -pix_fmt yuv420p out/eyes_flat_sbs.mp4

ls -lh out/
