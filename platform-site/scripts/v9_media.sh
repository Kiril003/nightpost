#!/usr/bin/env bash
# Site v9 media: scrub-friendly clips (GOP 4), day/night stills, self-hosted demo.
set -euo pipefail
S=~/phantom_ai/wt-film-v4/marketing/film/public/v6/clips
O=public/v9
mkdir -p $O
E=(-c:v libx264 -preset slow -crf 28 -tune animation -pix_fmt yuv420p -an -movflags +faststart -g 4 -keyint_min 4 -sc_threshold 0)
ff() { nice ffmpeg -loglevel error -y "$@"; }
ff -i $S/b02.mp4 -vf "scale=540:-2" "${E[@]}" $O/people.mp4
ff -ss 0.4 -t 4.9 -i $S/b08.mp4 -ss 1.9 -t 6.1 -i $S/b14.mp4 -t 5.33 -i $S/b09.mp4 -filter_complex \
  "[0:v]scale=540:-2,setsar=1,fps=30[a];[1:v]scale=540:-2,setsar=1,fps=30[b];[2:v]scale=540:-2,setsar=1,fps=30[c];[a][b][c]concat=n=3:v=1[v]" \
  -map "[v]" "${E[@]}" $O/flow.mp4
ff -i $S/b02.mp4 -vf "scale=540:-2" -frames:v 1 -c:v libwebp -quality 80 $O/people.webp
ff -ss 0.4 -i $S/b08.mp4 -vf "scale=540:-2" -frames:v 1 -c:v libwebp -quality 80 $O/flow.webp
ff -ss 1.25 -i $S/b13.mp4 -vf "scale=720:-2" -frames:v 1 -c:v libwebp -quality 84 $O/look-day.webp
ff -ss 2.75 -i $S/b13.mp4 -vf "scale=720:-2" -frames:v 1 -c:v libwebp -quality 84 $O/look-night.webp
# Beats b01, b02 and b04 say "circles" for people; the site demo keeps b03 (10.667-18.667 s) and b07 onward (26.667 s).
ff -i ~/phantom_ai/showcase-out/clockin-video/v6/nightpost-demo-colosseum.mp4 -filter_complex \
  "[0:v]trim=10.6667:18.6667,setpts=PTS-STARTPTS[v1];[0:a]atrim=10.6667:18.6667,asetpts=PTS-STARTPTS,afade=t=out:st=7.85:d=0.15[a1];\
[0:v]trim=start=26.6667,setpts=PTS-STARTPTS[v2];[0:a]atrim=start=26.6667,asetpts=PTS-STARTPTS,afade=t=in:d=0.15[a2];\
[v1][a1][v2][a2]concat=n=2:v=1:a=1[v][a];[v]scale=1280:-2[vs]" -map "[vs]" -map "[a]" \
  -c:v libx264 -preset slow -crf 25 -pix_fmt yuv420p -c:a aac -b:a 96k -movflags +faststart $O/demo.mp4
ff -ss 2 -i $O/demo.mp4 -frames:v 1 -c:v libwebp -quality 80 $O/demo.webp
