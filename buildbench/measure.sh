#!/bin/bash
# measure.sh CMD... : runs CMD inside this container and reports wall time, CPU time
# and peak memory from the container's own cgroup (cgroup v2, private cgroupns).
# Pure bash (no awk): some builder images are minimal.
anon() { while read -r k v; do [ "$k" = anon ] && { echo "$v"; return; }; done < /sys/fs/cgroup/memory.stat; }
( peak=0; while [ ! -e /tmp/.done ]; do a=$(anon); (( a > peak )) && peak=$a; echo "$peak" > /tmp/.anonpeak; sleep 0.5; done ) &
s=$EPOCHREALTIME
bash -c "$*" > /tmp/build.log 2>&1; rc=$?
e=$EPOCHREALTIME
touch /tmp/.done; sleep 0.7
while read -r k v; do [ "$k" = usage_usec ] && cpu=$v; done < /sys/fs/cgroup/cpu.stat
wall=$(( (${e/./} - ${s/./}) / 100000 ))
printf 'rc=%d wall=%d.%ds cpu=%d.%ds mem_peak=%dMB anon_peak=%dMB\n' $rc $((wall/10)) $((wall%10)) \
  $((cpu/1000000)) $(( (cpu/100000)%10 )) $(( $(cat /sys/fs/cgroup/memory.peak)/1048576 )) $(( $(cat /tmp/.anonpeak)/1048576 ))
[ $rc -ne 0 ] && tail -15 /tmp/build.log
exit 0
