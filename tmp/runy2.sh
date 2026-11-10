cd /c/fz/claudemenu/RonStrategist
S='C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves'
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard readied" -Seeds 6001,6002,6003,6004,6005,6006 -Parallel 6 -Out results/y2_isle.jsonl -Label y2I -Verbose2 -TimeoutMinutes 45 -Map "$S\[2p] Infinity_Isle 1.9" > tmp/y2I.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard readied" -Seeds 6001,6002,6003,6004,6005,6006 -Parallel 6 -Out results/y2_berl.jsonl -Label y2B -Verbose2 -TimeoutMinutes 45 -Map "$S\[2p] Berlingrad_Classic" > tmp/y2B.log 2>&1 &
wait
for f in results/y2_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; grep -o '"gameSeconds":[0-9]*' $f | paste -sd' '; done
exit 0
