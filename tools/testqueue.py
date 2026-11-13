#!/usr/bin/env python3
"""
One test queue for the whole machine, editable while it runs.

    py tools/testqueue.py            run tmp/queue.txt top to bottom, then wait for more lines

tmp/queue.txt, one suite per line (blank lines and # comments skipped):
    <suite> <label> [ron|prod|full] ...      e.g.  ron7lgs R7lgs ron        duellgs P_duellgs prod
  ron  = RON's own bot (-AltRon, dev server, every game)    prod = production server
  full = play every game (no early stop)    tNN = games end at NN minutes (screening)
A line is done once tmp/queue_done.txt lists its label; results go to tmp/queue_results.txt
(the suite report's TOTAL / sequential lines and the loss_report summary for RON runs).
Up to two batches run side by side under a global cap of 16 servers (selfplay.ps1 -GlobalMax), so the
second fills the slots the first leaves while its last games finish.
"""
import pathlib, subprocess, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
GLOBAL_MAX = 16      # test servers on the machine, all batches together
SIDE_BY_SIDE = 2     # batches running at once: the next one fills the slots the last games of the first leave
Q, DONE, OUT = ROOT / "tmp/queue.txt", ROOT / "tmp/queue_done.txt", ROOT / "tmp/queue_results.txt"


def running_suites():
    ps = ("$n = @(Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\" | "
          "Where-Object { $_.CommandLine -like ('*-File ' + 'suite.ps1*') }).Count; exit $n")
    return subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True).returncode


def next_line(skip=()):
    done = set(DONE.read_text(encoding="utf-8").split()) if DONE.exists() else set()
    if not Q.exists():
        return None
    for raw in Q.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] not in done and parts[1] not in skip:
            return parts
    return None


def run(parts):
    suite, label, flags = parts[0], parts[1], set(parts[2:])
    args = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "suite.ps1", "-Suite", suite, "-Label", label]
    # every batch may use the whole machine; the global cap keeps two side by side at 16 servers in all
    args += ["-Parallel", "16", "-ParallelIdle", "16", "-GlobalMax", str(GLOBAL_MAX)]
    # tNN: games end at NN minutes (screening; the timeouts still count half)
    for fl in flags:
        if fl.startswith("t") and fl[1:].isdigit():
            args += ["-TimeoutMinutes", fl[1:]]
    if "ron" in flags:
        args += ["-AltRon", "-Full"]
    else:
        if "prod" in flags:
            args += ["-Prod"]
        if "full" in flags:
            args += ["-Full"]
    (ROOT / "results" / f"{label}.jsonl").unlink(missing_ok=True)
    t0 = time.time()
    rep = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    lines = [l for l in rep.splitlines() if l.startswith("TOTAL") or "sequential" in l]
    if "ron" in flags:
        lr = subprocess.run(["py", "tools/loss_report.py", f"results/{label}.jsonl"], cwd=ROOT, capture_output=True, text=True,
                            encoding="utf-8", errors="replace").stdout.splitlines()
        lines += [l for l in lr if l.startswith("  ") and l.strip()[:1] in "WABCDE" and "(" in l]
    with OUT.open("a", encoding="utf-8") as f:
        f.write(f"== {suite} {label} ({int(time.time() - t0) // 60} min)\n" + "\n".join(lines) + "\n")
    with DONE.open("a", encoding="utf-8") as f:
        f.write(label + "\n")
    print(f"== {suite} {label}", flush=True)
    for l in lines:
        print(l, flush=True)


def main():
    import threading
    active = {}   # label -> thread
    while True:
        for k in [k for k, t in active.items() if not t.is_alive()]:
            del active[k]
        # batches started by someone else (an older runner) count too
        if len(active) < SIDE_BY_SIDE and running_suites() < SIDE_BY_SIDE:
            parts = next_line(skip=set(active))
            if parts:
                t = threading.Thread(target=run, args=(parts,), daemon=True)
                active[parts[1]] = t
                t.start()
                time.sleep(90)   # let its servers start before looking again
                continue
        time.sleep(30)


if __name__ == "__main__":
    main()
