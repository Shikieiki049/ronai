# per label: share of expert status samples in the first 600 s that are supply-blocked (pop >= cap, cap < 150)
cd /c/fz/claudemenu/RonStrategist
for X in "$@"; do
  cat work/${X}*-*/logs/latest.log 2>/dev/null | grep -a 'T1-expert-1 \[专家\] workers' | grep -a -B0 '' | awk '
  { if (match($0,/pop=[0-9]+\/[0-9]+/)) { split(substr($0,RSTART+4,RLENGTH-4),a,"/"); } }
  { n++ }' >/dev/null
  cat work/${X}*-*/logs/latest.log 2>/dev/null | grep -a 'autotest t=\|T1-expert-1 \[专家\] workers' | awk -v X=$X '
  /autotest t=/{t=$0; sub(/.*t=/,"",t); sub(/s.*/,"",t); t+=0; next}
  { if (t>0 && t<=600 && match($0,/pop=[0-9]+\/[0-9]+/)) { split(substr($0,RSTART+4,RLENGTH-4),a,"/"); n++; if (a[1]+0>=a[2]+0 && a[2]+0<150) h++ } }
  END { printf "%s housed %d/%d = %.0f%%\n", X, h, n, 100*h/(n?n:1) }'
done
