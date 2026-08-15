# Evaluation workspace

Offline analysis of the MOCCA offloading middleware: how the rule-based policy
compares against local-only and cloud-only baselines, how accurate the cost
model's estimators are, and whether a Random Forest can learn the policy from
observed device context.

## Folder layout

```
evaluation/
├── PHASE1_DATA_COLLECTION.md         # how to generate training.csv
├── PHASE2_4_ON_DEVICE_DEPLOYMENT.md  # how to ship a retrained model to Android
├── README.md                          # this file
├── collect_data.ps1                   # automated 11-session collection
├── verify_dataset.ps1                 # post-hoc checks on a collected CSV
├── data/
│   └── training.csv                   # produced by collect_data.ps1
├── notebooks/
│   └── random-forest-training.ipynb   # analysis + model export
└── outputs/                           # produced by the notebook — commit these
    ├── confusion-matrix.png
    ├── feature-importance.png
    ├── latency-by-policy.png
    ├── estimator-accuracy.png
    ├── energy-validation.png
    ├── regret-by-policy.png
    ├── comparison-summary.md
    └── rf-model.json
```

## Quickstart

```powershell
# 1. One-time environment setup (from the repo root)
python -m venv .venv-dev
.\.venv-dev\Scripts\Activate.ps1
pip install -r requirements-dev.txt -r edge-server/requirements.txt

# 2. Run the test suites before trusting anything they cover
pytest                                    # servers + shared task registry
cd mobile; ./gradlew :app:testDebugUnitTest; cd ..   # policy, estimators, CSV, RF model

# 3. Collect data (phone on USB, docker compose up -d)
.\evaluation\collect_data.ps1

# 4. Check the dataset is usable — collection writes the CSV either way
.\evaluation\verify_dataset.ps1

# 5. Analyse
jupyter notebook evaluation/notebooks/random-forest-training.ipynb
```

The notebook fails fast if `training.csv` predates the current CSV schema —
re-collect rather than trying to patch an old file.

## What the notebook produces

| Section | Question it answers |
|---------|--------------------|
| 4 | Is the data trustworthy? (fallbacks, errors, edge→cloud forwarding) |
| 8–9 | Can a Random Forest reproduce the rule engine, and by how much does it beat the majority-class floor? |
| 10 | Which context signals drive decisions, and would energy features help? |
| 11 | How do the policies compare on mean, median, p95 and p99 latency? |
| 11 | Are those differences real, or sampling noise? (bootstrap CIs) |
| 12 | How accurate are `LatencyEstimator`'s predictions? (MAE / MAPE / bias) |
| 12 | How much of remote latency is network vs server compute? |
| 13 | Is the energy model internally consistent with measured timings? |
| 14 | Under what conditions does offloading fail and fall back? |
| 15 | **Do the policies make *good* decisions**, not just consistent ones? |

## The circularity problem, and what we do about it

The Random Forest is trained on labels the rule engine produced. So its accuracy
measures **imitation, not decision quality** — a model scoring 1.00 has perfectly
reproduced a policy that might itself be wrong. Reporting only accuracy would be
circular, and is the first thing a reviewer should attack.

Section 15 addresses this with a **matched-condition oracle**. Rows are bucketed
by task and by discretised context (network score, battery, CPU); within each
bucket, the empirically fastest tier is treated as the choice an oracle would
have made, and regret is each row's latency minus that bucket's best. It is an
approximation — the true counterfactual was never observed, since each task ran
under exactly one target — but it is non-circular, and it is what lets the thesis
claim the adaptive policy is *better*, not merely *self-consistent*.

The approximation's limits (dropped buckets, coarse bucketing, within-bucket
variance treated as noise) belong in the limitations section, stated plainly.

## Design decisions

| Decision | Rationale |
|----------|-----------|
| Random Forest (not a single Decision Tree) | Better accuracy, still interpretable via feature importance |
| No deep learning / no RL | Supervisor explicitly excluded; RF fits the "lightweight" requirement |
| Jupyter notebook (not a `.py` script) | Plots and intermediate output embed directly into the thesis chapter |
| Manual JSON export (not TFLite / ONNX) | No new APK dependency, ~90 KB model, fully auditable Kotlin walker |
| scikit-learn (not PyTorch / TF) | One `pip install`, no GPU, deterministic, defensible |
| Stratified split + `class_weight='balanced'` | The rule distribution is uneven by construction |
| Accuracy reported against a majority-class floor | Raw accuracy is misleading on skewed labels |
| Bootstrap CIs (not t-tests) | Latency distributions are right-skewed; normality does not hold |
| Latency attributed by `executed_at`, not `target` | The edge forwards to the cloud under overload |

## Mapping to the thesis chapter

- **5.1 Methodology** — collection runbook, session design, data-integrity checks (notebook §4)
- **5.2 Feature engineering** — the 12 features and their encodings (§6)
- **5.3 Classifier results** — accuracy vs floor, CV, confusion matrix, feature importance (§8–10)
- **5.4 Cost-model validation** — estimator MAE/MAPE/bias, network vs compute split, energy consistency (§12–13)
- **5.5 Policy comparison** — latency by policy with tails and CIs, fallback behaviour (§11, §14)
- **5.6 Decision quality** — regret vs matched-condition oracle, and why accuracy alone is circular (§15)
- **5.7 Limitations and future work** — oracle approximation, fixed power coefficients, energy features excluded from the deployed model, single-device study
