cd /c/fz/claudemenu/RonStrategist
S='C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves'
run() { k=$1; CD=""; [ -n "$2" ] && CD="C:/fz/claudemenu/RonStrategist/work/$2/classes"
  for m in "[4p] 4Mountains" "[6p] Burnt-Out_Paradise"; do
    t=${m:5:2}
    powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CD:+-ClassesDir "$CD"} -Spec "expert vs hard*2" -Seeds 4101,4102,4103,4104,4105,4106 -Parallel 3 -Out results/LD${k}_$t.jsonl -Label LD${k}$t -Verbose2 -TimeoutMinutes 45 -Map "${S}/${m}" > tmp/LD${k}_$t.log 2>&1 &
  done; }
run V0 _snap-254b06fa; run V1 _snap-02650b44; run V2 _snap-8b904d33; run V3 _snap-00decab5; run V4 ""
wait
for k in V0 V1 V2 V3 V4; do w=0; n=0; to=0; for f in results/LD${k}_*.jsonl; do [ -f $f ] || continue; n=$((n+$(wc -l <$f))); w=$((w+$(grep -c '"winnerTeam":0,"timeout":false' $f))); to=$((to+$(grep -c '"timeout":true' $f))); done; mins=$(cat results/LD${k}_*.jsonl 2>/dev/null | grep '"winnerTeam":0,"timeout":false' | grep -o '"gameSeconds":[0-9]*' | cut -d: -f2 | awk '{s+=$1}END{if(NR)printf "%.1f", s/NR/60}'); echo "$k win=$w/$n timeouts=$to winMin=$mins  $(for f in results/LD${k}_*.jsonl; do echo -n "$(basename $f .jsonl)=$(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f) "; done)"; done
