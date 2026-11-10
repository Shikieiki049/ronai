# avg workers at 180/300/420/600s and army at 600/900s over the given work dirs
for d in "$@"; do
  L=$d/logs/latest.log; [ -f "$L" ] || continue
  grep -a -A8 "autotest t=" "$L" | grep -aE "autotest t=|T1-expert-1 \[" | grep -oE "t=[0-9]+s|workers=[0-9]+|army=[0-9]+" | paste - - - | tr -d 'ts=workerarmy' | awk '{print $1, $2, $3}'
done | awk '{ if($1==180){a+=$2;na++} if($1==300){b+=$2;nb++} if($1==420){c+=$2;nc++} if($1==600){d+=$2;e+=$3;nd++} if($1==900){f+=$3;nf++} }
  END { printf "w180=%.1f w300=%.1f w420=%.1f w600=%.1f army600=%.1f army900=%.1f (n=%d)\n", a/na, b/nb, c/nc, d/nd, e/nd, f/nf, nd }'
