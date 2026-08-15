# Setting up a data-collection machine

Everything a fresh machine needs to run `evaluation/collect_data.ps1` end to end.
For analysis only (running the notebook against an existing `training.csv`) skip
to [Analysis-only setup](#analysis-only-setup) — it needs none of the Android or
Docker tooling.

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| JDK | 17 | `java -version` |
| Android SDK | API 34, platform-tools | `adb version` |
| Docker Desktop | any recent | `docker ps` |
| Python | 3.11+ | `python --version` |
| PowerShell | 5.1 or 7+ | built into Windows |

An Android phone with **USB debugging enabled**, on the **same Wi-Fi network** as
this machine.

## 1. Clone

```powershell
git clone https://github.com/PhuXuan0511/CloudAware-MOCCA-A-Context-Aware-Self-Adaptive-Middleware-for-Mobile-Cloud-and-Edge-Offloading.git
cd CloudAware-MOCCA-A-Context-Aware-Self-Adaptive-Middleware-for-Mobile-Cloud-and-Edge-Offloading
git checkout evaluation-hardening
```

## 2. Point Gradle at the Android SDK

`local.properties` is gitignored, so it does not travel with the repo. Either set
`ANDROID_HOME`, or create the file:

```powershell
# Option A — environment variable (per-session)
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Option B — checked-in-free local file
"sdk.dir=$($env:LOCALAPPDATA -replace '\\','\\\\')\\\\Android\\\\Sdk" |
  Out-File -Encoding utf8 mobile/local.properties
```

Verify: `cd mobile; ./gradlew :app:testDebugUnitTest` should report 82 passing tests.

## 3. Start the servers

```powershell
docker compose -f docker/docker-compose.yml up -d --build
```

`--build` is required, not optional. The images install `iproute2` for `tc`, and
compose grants `NET_ADMIN` — without both, the collection script cannot shape the
network at all: Session C records healthy-network rows labelled as degraded, and
the cloud tier measures no further away than the edge.

### Emulated topology — declare this in the thesis

Both servers are containers on this one machine, so "cloud" is not physically
distant. `collect_data.ps1` installs a persistent one-way delay on the cloud
container (`-CloudRttMs`, default 80 ms) to stand in for the distance to a
datacentre, and applies degradation sessions to **both** tiers because a
degraded access link affects every remote path, not just the near one.

The cloud tier's network cost is therefore a number chosen in the script, not a
measurement against a real provider. Report it as emulated. Without it, edge and
cloud latency come out equal and no edge-versus-cloud conclusion is supportable.

Confirm:

```powershell
Invoke-RestMethod http://localhost:8001/health          # {status: ok, node: edge}
Invoke-RestMethod http://localhost:8002/health          # {status: ok, node: cloud}
Invoke-RestMethod http://localhost:8001/api/v1/status   # metrics_source must not say "psutil"
```

## 4. Install the app

```powershell
cd mobile
./gradlew :app:installDebug
cd ..
```

The app archives any existing `mocca-metrics.csv` on the phone when the CSV
schema changes, so an older data file will not be mixed into the new one.

## 5. Point the phone at *this* machine — the step that catches people out

The app's built-in defaults are `http://10.0.2.2:8001` / `:8002`. That address is
the **Android emulator's** alias for its host and means nothing to a physical
phone on Wi-Fi. A real device must be given this machine's LAN IP.

```powershell
# Find the LAN address (ignore Docker/WSL/virtual adapters — you want 192.168.x.x)
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } |
  Select-Object InterfaceAlias, IPAddress
```

Then on the phone: **Settings → Edge URL / Cloud URL**

```
http://<that-ip>:8001
http://<that-ip>:8002
```

Verify from the phone's browser that `http://<that-ip>:8001/health` responds
before starting collection. If it times out, the usual cause is Windows Defender
Firewall blocking inbound connections:

```powershell
# Run as Administrator
New-NetFirewallRule -DisplayName "MOCCA edge"  -Direction Inbound -LocalPort 8001 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "MOCCA cloud" -Direction Inbound -LocalPort 8002 -Protocol TCP -Action Allow
```

A high fallback rate in the final report almost always traces back to this step.

## 6. Collect

```powershell
adb devices          # the phone must appear as "device", not "unauthorized"
.\evaluation\collect_data.ps1
```

Roughly 45–60 minutes. Pre-flight verifies server health, edge headroom, and that
`tc`/netem actually works before committing to the run. Session H pauses twice
for the mobile-hotspot setup, so stay nearby.

## 7. Check the dataset

Collection always writes `evaluation/data/training.csv`, whatever happened during
the run — so the CSV existing tells you nothing about whether it is usable. That
is a separate question, and a separate script:

```powershell
.\evaluation\verify_dataset.ps1
```

Reports row count, rule distribution, task balance, fallback rate, `target` vs
`executed_at` agreement, observed RTT spread, and whether the cloud measured
further away than the edge. Re-runnable on any CSV via `-Path`, so you can check
an archived file or re-check after re-collecting a single session.

## 7. Analyse

```powershell
python -m venv .venv-dev
.\.venv-dev\Scripts\Activate.ps1
pip install -r requirements-dev.txt
jupyter notebook evaluation/notebooks/random-forest-training.ipynb
```

## Analysis-only setup

If this machine just runs the notebook against a `training.csv` collected
elsewhere, you need only Python:

```powershell
git clone <repo> && cd <repo> && git checkout evaluation-hardening
python -m venv .venv-dev
.\.venv-dev\Scripts\Activate.ps1
pip install -r requirements-dev.txt
```

Copy `training.csv` into `evaluation/data/`, then run the notebook. The notebook
fails fast with the list of missing columns if the CSV predates the current
26-column schema.

## Moving data between machines

`evaluation/data/` is not gitignored, so the CSV can travel through git:

```powershell
git add evaluation/data/training.csv
git commit -m "Add Phase 1 collection data"
git push
```

Expect 400–900 rows, well under a megabyte.

## Troubleshooting

| Symptom | Cause |
|---------|-------|
| Notebook: `training.csv is missing [...]` | CSV written by an older build — reinstall the app and re-collect |
| High fallback rate (>20%) | Phone cannot reach the server URLs — step 5 |
| `target != executed_at` >5% | Edge is overloaded and forwarding to cloud — raise `mem_limit` or `MOCCA_OVERLOAD_MEM_PERCENT` |
| `UNSTABLE_NETWORK` has almost no rows | netem is not applying — rebuild with `--build`, confirm `cap_add: NET_ADMIN` |
| `tc: not found` in pre-flight | Image built before `iproute2` was added — `up -d --build` |
| Gradle: `SDK location not found` | Step 2 |
| `adb devices` shows `unauthorized` | Accept the USB-debugging prompt on the phone |
