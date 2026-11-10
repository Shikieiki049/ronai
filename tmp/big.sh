# X=<label> bash tmp/big.sh : runA round + 6 extra Cabana and Isle seeds, then summary
cd /c/fz/claudemenu/RonStrategist
S='C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves'; P='C:\fz\claudemenu\RonStrategist\tmp\maps'
rm -f results/${X}x_*
powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CLS:+-ClassesDir "$CLS"} -Spec "expert vs hard readied" -Seeds 6007,6008,6009,6010,6011,6012 -Parallel 6 -Out results/${X}x_isle.jsonl -Label ${X}J -Verbose2 -TimeoutMinutes 45 -Map "$S\[2p] Infinity_Isle 1.9" > tmp/${X}J.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CLS:+-ClassesDir "$CLS"} -Spec "expert vs hard readied" -Seeds 7007,7008,7009,7010,7011,7012 -Parallel 6 -Out results/${X}x_cab.jsonl -Label ${X}K -Verbose2 -TimeoutMinutes 45 -Map "$P\Cabana_Republic" > tmp/${X}K.log 2>&1 &
bash tmp/runA.sh > tmp/${X}_summary.txt 2>&1
wait
for L in C B I U S J K; do w=0; n=0; for d in work/${X}$L-*/; do [ -f $d/logs/latest.log ] || continue; r=$(grep -a 'autotest result:' $d/logs/latest.log | tail -1); [ -z "$r" ] && continue; n=$((n+1)); echo "$r" | grep -q '"winnerTeam":0,"timeout":false' && w=$((w+1)); done; printf "%s %d/%d  " $L $w $n; T=$((T+w)); N=$((N+n)); done; echo " total $T/$N"
bash tmp/tl.sh
