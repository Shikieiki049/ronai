# usage: devmetric.sh <work-dir-glob...>; prints seconds to reach 20/30/40 workers and army at 600s/900s
for d in "$@"; do
  L=$d/logs/latest.log; [ -f "$L" ] || continue
  grep -a -A8 "autotest t=" "$L" | grep -aE "autotest t=|T1-expert-1 \[" | grep -oE "t=[0-9]+s|workers=[0-9]+|army=[0-9]+" | paste - - - | awk -v d="$(basename $d)" '
    { t=$1; sub("t=","",t); sub("s","",t); w=$2; sub("workers=","",w); a=$3; sub("army=","",a);
      if(!w20 && w>=20) w20=t; if(!w30 && w>=30) w30=t; if(!w40 && w>=40) w40=t; if(t==600) a6=a; if(t==900) a9=a }
    END { printf "%s w20=%s w30=%s w40=%s army600=%s army900=%s\n", d, w20, w30, w40, a6, a9 }'
done
