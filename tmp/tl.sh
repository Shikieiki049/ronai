# usage: X=N bash tmp/tl.sh  -> average expert timeline of a runA round
cd /c/fz/claudemenu/RonStrategist
for d in work/${X}[CBIUS]-*/; do l=$d/logs/latest.log; [ -f $l ] || continue
 grep -a 'autotest t=\|T1-expert-1 \[专家\]' $l | awk '/autotest t=/{t=$0; sub(/.*t=/,"",t); sub(/s.*/,"",t); t=t+0; next} /专家/{if((t==300||t==600||t==900||t==1200) && !s[t]++){ match($0,/workers=[0-9]+/); w=substr($0,RSTART+8,RLENGTH-8); match($0,/army=[0-9]+/); a=substr($0,RSTART+5,RLENGTH-5); match($0,/farms=[0-9]+/); f=substr($0,RSTART+6,RLENGTH-6); print t, w, a, f}}'
done | awk '{w[$1]+=$2; a[$1]+=$3; f[$1]+=$4; n[$1]++} END{for(k in n) printf "t=%s workers=%.1f army=%.1f farms=%.1f n=%d\n",k,w[k]/n[k],a[k]/n[k],f[k]/n[k],n[k]}' | sort -t= -k2 -n
