# expert vs hard*2 head-to-head: bash tmp/hard2.sh LABEL [classesDir]
cd /c/fz/claudemenu/RonStrategist
P='C:\fz\claudemenu\RonStrategist\tmp\maps'
L=$1; CD=$2
for m in 4Mountains Burnt-Out_Paradise; do
  powershell -ExecutionPolicy Bypass -File selfplay.ps1 ${CD:+-ClassesDir "$CD"} -Spec "expert vs hard*2" -Seeds 4101,4102,4103,4104,4105,4106,4107,4108,4109,4110 -Parallel 5 -Out results/H${L}_${m:0:4}.jsonl -Label H${L}${m:0:4} -Verbose2 -TimeoutMinutes 45 -Map "${P}/${m}" > tmp/H${L}_${m:0:4}.log 2>&1 &
done
wait
