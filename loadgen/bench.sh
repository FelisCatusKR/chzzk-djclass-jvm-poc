#!/usr/bin/env bash
# bench.sh NAME IMAGE [extra podman run args...]
# startup time, idle RSS, RSS/CPU under load (20 channels x 5 widgets, 5 msg/s each), stop time.
set -u
here=$(cd "$(dirname "$0")" && pwd)   # needs ./loadgen and ./seedtool: go build -o loadgen . && go build -o seedtool ./seed
name=$1 image=$2; shift 2
data=/tmp/bench-data-$name
podman rm -f "b-$name" >/dev/null 2>&1; podman unshare rm -rf "$data"; mkdir -p "$data"; chmod 777 "$data"
t0=$(date +%s%N)
podman run -d --name "b-$name" -p 18080:8000 -v "$data":/data \
  -e BASE_URL=http://localhost:8000 -e DEV=1 -e CHZZK_CLIENT_ID=x -e CHZZK_CLIENT_SECRET=x -e VARCHIVE_TOKEN_KEY=x \
  "$@" "$image" >/dev/null
until curl -sf -o /dev/null localhost:18080/healthz; do sleep 0.05; done
t1=$(date +%s%N)
pid=$(podman inspect -f '{{.State.Pid}}' "b-$name")
rss() { awk '/VmRSS/{print int($2/1024)}' /proc/$pid/status; }
cpu() { awk '{print $14+$15}' /proc/$pid/stat; }   # clock ticks (1/100 s)
podman unshare $here/seedtool "$data/djclass.sqlite3" >/dev/null
sleep 20; idle=$(rss)
c0=$(cpu); peak=0
( while sleep 1; do r=$(rss 2>/dev/null) || break; echo "$r"; done ) > "/tmp/bench-rss-$name" &
sampler=$!
out=$($here/loadgen -base http://localhost:18080 -c 20 -n 5 -rate 5 -d 90s)
kill $sampler; peak=$(sort -n "/tmp/bench-rss-$name" | tail -1)
c1=$(cpu); after=$(rss)
s0=$(date +%s%N); podman stop -t 30 "b-$name" >/dev/null; s1=$(date +%s%N)
printf '%-10s start=%5dms idle=%4dMB peak=%4dMB after=%4dMB cpu=%5.1fs stop=%5dms | %s\n' \
  "$name" $(( (t1-t0)/1000000 )) "$idle" "$peak" "$after" "$(awk -v a=$c0 -v b=$c1 'BEGIN{print (b-a)/100}')" $(( (s1-s0)/1000000 )) "$out"
podman rm -f "b-$name" >/dev/null
