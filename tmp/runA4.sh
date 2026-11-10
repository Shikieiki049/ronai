cd /c/fz/claudemenu/RonStrategist
S='C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves'
P='C:\fz\claudemenu\RonStrategist\tmp\maps'
X=${X:-D}
if [ -z "$SUMMARY_ONLY" ]; then
rm -f results/${X}_*
powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CLS:+-ClassesDir "$CLS"} -Spec "expert vs hard readied" -Seeds 7001,7002,7003,7004,7005,7006 -Parallel 4 -Out results/${X}_cabana.jsonl -Label ${X}C -Verbose2 -TimeoutMinutes 45 -Map "$P\Cabana_Republic" > tmp/${X}C.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CLS:+-ClassesDir "$CLS"} -Spec "expert vs hard readied" -Seeds 6001,6002,6003,6004,6005,6006 -Parallel 4 -Out results/${X}_berl.jsonl -Label ${X}B -Verbose2 -TimeoutMinutes 45 -Map "$P\Berlingrad_Classic" > tmp/${X}B.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CLS:+-ClassesDir "$CLS"} -Spec "expert vs hard readied" -Seeds 6001,6002,6003,6004,6005,6006 -Parallel 4 -Out results/${X}_isle.jsonl -Label ${X}I -Verbose2 -TimeoutMinutes 45 -Map "$P\Infinity_Isle" > tmp/${X}I.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CLS:+-ClassesDir "$CLS"} -Spec "expert vs hard readied" -Seeds 5001,5002,5003,5004,5005,5006 -Parallel 4 -Out results/${X}_burnt.jsonl -Label ${X}U -Verbose2 -TimeoutMinutes 45 -Map "$P\Burnt-Out_Paradise" > tmp/${X}U.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CLS:+-ClassesDir "$CLS"} -Spec "expert vs hard readied" -Seeds 8001,8002,8003,8004,8005,8006 -Parallel 4 -Out results/${X}_snow.jsonl -Label ${X}S -Verbose2 -TimeoutMinutes 45 -Map "$P\Snowplow" > tmp/${X}S.log 2>&1 &
wait
fi
for f in results/${X}_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; done
for d in work/${X}C-*/ work/${X}B-*/ work/${X}I-*/ work/${X}U-*/ work/${X}S-*/; do [ -f $d/logs/latest.log ] || continue; grep -a "autotest t=\|T1-expert-1 \[专家\]" $d/logs/latest.log | awk -v D=$(basename $d | cut -c1-2)$(basename $d | cut -c24-28) '/autotest t=/{t=$0; sub(/.*t=/,"",t); sub(/s.*/,"",t); next} /专家/{if((t==300||t==600||t==900||t==1200) && !s[t]++) { match($0,/farms=[0-9]+/); printf "%s %s w%s %s %s | ", D, t, substr($0, index($0,"workers=")+8,2), substr($0, index($0,"army="), 8), substr($0,RSTART,RLENGTH)}} END{print ""}'; done
exit 0
