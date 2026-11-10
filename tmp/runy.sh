cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard readied" -Seeds 6001,6002,6003,6004,6005,6006 -Parallel 6 -Out results/y_isle.jsonl -Label yI -Verbose2 -TimeoutMinutes 45 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[2p] Infinity_Isle 1.9' > tmp/yI.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard readied" -Seeds 6001,6002,6003,6004,6005,6006 -Parallel 6 -Out results/y_berl.jsonl -Label yB -Verbose2 -TimeoutMinutes 45 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[2p] Berlingrad_Classic' > tmp/yB.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard readied" -Seeds 5001,5002,5003,5004,5005,5006 -Parallel 6 -Out results/y_burnt.jsonl -Label yU -Verbose2 -TimeoutMinutes 45 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/yU.log 2>&1 &
wait
for f in results/y_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; done
for d in work/yI-*/ work/yB-*/ work/yU-*/; do grep -a "autotest t=\|T1-expert-1 \[专家\]" $d/logs/latest.log | awk -v D=$(basename $d | cut -c1-2)$(basename $d | cut -c24-28) '/autotest t=/{t=$0; sub(/.*t=/,"",t); sub(/s.*/,"",t); next} /专家/{if((t==300||t==600||t==900||t==1200) && !s[t]++) printf "%s %s w%s %s | ", D, t, substr($0, index($0,"workers=")+8,2), substr($0, index($0,"army="), 8)} END{print ""}'; done
grep -ah "edge attack\|asking for a bridge\|bridge needed\|clearing neutral" work/yI-*/logs/latest.log work/yB-*/logs/latest.log work/yU-*/logs/latest.log | awk '{print substr($0,index($0,"expert]")+8,22)}' | sort | uniq -c | sort -rn | head
exit 0
