# Phase 2 — Random Forest evaluation workspace

Offline analysis comparing the rule-based offloading policy against a
Random Forest classifier trained on data collected from the running app.

## Folder layout

```
evaluation/
├── PHASE1_DATA_COLLECTION.md         # how to generate training.csv
├── PHASE2_4_ON_DEVICE_DEPLOYMENT.md  # how to ship the trained model to Android
├── README.md                          # this file
├── data/
│   └── training.csv                   # populated from `adb shell run-as ...`
├── notebooks/
│   └── random-forest-training.ipynb   # train RF + export rf-model.json
└── outputs/
    ├── feature-importance.png
    ├── confusion-matrix.png
    ├── comparison-summary.md
    └── rf-model.json                  # deployable to mobile/app/src/main/assets/
```

## Quickstart

```powershell
# 1. Set up a Python venv (one-time)
python -m venv .venv-evaluation
.\.venv-evaluation\Scripts\Activate.ps1
pip install pandas scikit-learn matplotlib seaborn jupyter

# 2. Collect data via the runbook in PHASE1_DATA_COLLECTION.md
# (run demo sessions A-G, then pull CSV)
adb shell "run-as com.thesis.middleware cat files/mocca-metrics.csv" `
  > evaluation/data/training.csv

# 3. Open the notebook and run cells top-to-bottom
jupyter notebook evaluation/notebooks/random-forest-training.ipynb

# 4. If test accuracy >= 0.85, deploy on-device per PHASE2_4_ON_DEVICE_DEPLOYMENT.md
```

## Phase order

1. **Phase 1 — Data collection**: app runtime is the data source.
   The CSV schema is defined in `MetricsRecorder.kt`. Follow
   `PHASE1_DATA_COLLECTION.md` to ensure all 7 rules are represented
   and both baseline modes (LOCAL_ONLY, CLOUD_ONLY) are sampled.

2. **Phase 2 — Train + evaluate**: the notebook handles loading,
   feature engineering, training, evaluation, and model export.
   Outputs go to `outputs/`.

3. **Phase 2.4 — On-device deployment**: copy `outputs/rf-model.json`
   to `mobile/app/src/main/assets/`, add `RandomForestPolicy.kt`,
   extend `ExecutionMode` with `ADAPTIVE_ML`. Architecture in
   `PHASE2_4_ON_DEVICE_DEPLOYMENT.md`.

## Why these choices

| Decision | Rationale |
|----------|-----------|
| Random Forest (not Decision Tree) | Better accuracy, still interpretable via feature importance |
| No deep learning / no RL | Supervisor explicitly excluded; RF fits the "lightweight" requirement |
| Jupyter notebook (not .py) | Easier to embed plots + intermediate output in the thesis chapter |
| Manual JSON model export (not TFLite/ONNX) | No new APK dependency, ~10–50 KB model size, fully auditable Kotlin walker |
| sklearn (not pytorch / tf) | Single `pip install`, no GPU, deterministic, defensible |
| Stratified train/test split | Counteracts uneven rule distribution in the CSV |

## What to put in the thesis evaluation chapter

From the notebook outputs:
- Section 5.1 — Methodology: describe how the CSV was collected (cite the runbook)
- Section 5.2 — Feature engineering: list the 12 features and their encodings
- Section 5.3 — Results: include `feature-importance.png`, `confusion-matrix.png`, accuracy + CV scores
- Section 5.4 — Discussion: where RF agrees / disagrees with rule-based
- Section 5.5 — Latency comparison: Local-only vs Cloud-only vs Rule-based vs ML
- Section 5.6 — Limitations + future work: more data, RL, online learning
