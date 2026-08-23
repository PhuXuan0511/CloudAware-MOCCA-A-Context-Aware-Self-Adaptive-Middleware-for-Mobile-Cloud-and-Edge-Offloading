# CloudAware-MOCCA

A context-aware self-adaptive middleware for mobile cloud and edge offloading.

CloudAware-MOCCA decides, at runtime and per task, whether a mobile workload
should run on the device (LOCAL), on a nearby edge server (EDGE), or in the
cloud (CLOUD). The decision is driven by a full MAPE-K control loop that
monitors device and network context, analyses the cost of each placement,
plans the best target, and executes it, falling back safely when a remote tier
is unreachable.

## Overview

The system has three cooperating parts:

1. An Android application that runs the adaptation loop on the device.
2. An edge server and a cloud server that execute offloaded tasks.
3. An evaluation workspace that collects real execution data and trains a
   learned placement model.

Placement is made by a deterministic rule engine over a set of context and
cost features. A compact Random Forest classifier is trained to imitate that
rule engine so the same decisions can be reproduced on device at negligible
inference cost. Decision quality is measured against a matched-condition
oracle rather than against the rule-generated labels, so the reported quality
number does not depend on the policy that produced the labels.

## Repository layout

| Path | Contents |
|------|----------|
| `mobile/` | Android application (Kotlin): MAPE-K loop, context collectors, rule engine, estimators, Random Forest inference, communication layer, metrics logging, demo UI |
| `edge-server/` | Edge tier (Python, FastAPI): task executor, resource monitor, and a gateway that forwards to the cloud under load |
| `cloud-server/` | Cloud tier (Python, FastAPI): task executor and resource monitor |
| `shared/` | Shared Python models, resource metrics, and the task registry |
| `docker/` | Dockerfiles and Compose definition for the edge and cloud containers, including network shaping |
| `evaluation/` | Data-collection scripts, the collected dataset, the Random Forest training notebook, analysis, and the evaluation writeup |
| `implementation/` | Implementation writeup and architecture figures |
| `tests/` | Python test suite |

## Architecture

The middleware follows the MAPE-K pattern:

- Monitor: context collectors read battery, CPU load, network type, measured
  round-trip time, bandwidth, and mobility state.
- Analyze: latency and energy estimators predict the cost of local versus
  remote execution for the current task and context.
- Plan: the rule engine applies a priority-ordered set of named rules
  (for example compute floor, low battery offload, and balanced cost) to
  choose LOCAL, EDGE, or CLOUD.
- Execute: the communication layer dispatches the task to the chosen tier and
  falls back to a safe alternative if the remote tier fails.
- Knowledge: every decision and its outcome are logged to a CSV for later
  analysis and model training.

The edge server can forward a request to the cloud when it is overloaded, so a
task labelled EDGE may be executed on the cloud transparently under load.

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| JDK | 17 | `java -version` |
| Android SDK | API 34 with platform-tools | `adb version` |
| Docker Desktop | any recent release | `docker ps` |
| Python | 3.11 or newer | `python --version` |
| PowerShell | 5.1 or 7+ | built into Windows |

For live data collection you also need an Android phone with USB debugging
enabled, on the same Wi-Fi network as the host machine.

## Build and run

### 1. Start the servers

```powershell
docker compose -f docker/docker-compose.yml up -d --build
```

The `--build` flag is required. The images install network tooling and the
Compose file grants the capability needed to shape the network for the
degraded-condition scenarios. Confirm both tiers are healthy:

```powershell
Invoke-RestMethod http://localhost:8001/health   # edge
Invoke-RestMethod http://localhost:8002/health   # cloud
```

### 2. Build and install the app

Point Gradle at the Android SDK by setting `ANDROID_HOME` or creating
`mobile/local.properties` with `sdk.dir`, then:

```powershell
cd mobile
./gradlew :app:installDebug
cd ..
```

### 3. Point the phone at the host

The app defaults target the Android emulator host alias, which means nothing to
a physical phone. On the device, open Settings and set the Edge URL and Cloud
URL to the host machine LAN address:

```
http://<host-lan-ip>:8001
http://<host-lan-ip>:8002
```

If the phone cannot reach these URLs, allow inbound TCP on ports 8001 and 8002
through the host firewall.

## Evaluation

The evaluation workspace collects real execution data from the device and
trains the placement model.

```powershell
# Collect a dataset from the connected phone
.\evaluation\collect_data.ps1

# Check the resulting dataset
.\evaluation\verify_dataset.ps1

# Train and analyse
python -m venv .venv-dev
.\.venv-dev\Scripts\Activate.ps1
pip install -r requirements-dev.txt
jupyter notebook evaluation/notebooks/random-forest-training.ipynb
```

The collected dataset lives in `evaluation/data/training.csv`. The evaluation
findings are written up in `evaluation/EVALUATION.md`, and the implementation
is documented in `implementation/IMPLEMENTATION.md`.

## Tests

```powershell
# Python suite
python -m pytest

# Android unit tests
cd mobile
./gradlew :app:testDebugUnitTest
```
