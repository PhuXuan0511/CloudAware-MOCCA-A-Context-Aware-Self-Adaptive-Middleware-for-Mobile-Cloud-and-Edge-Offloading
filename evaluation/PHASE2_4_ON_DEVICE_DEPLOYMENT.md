# Phase 2.4 — On-Device Random Forest Deployment

Goal: Run the trained Random Forest on the phone alongside the existing
rule-based policy, so the audience can switch between **Adaptive (rules)**
and **Adaptive (ML)** at runtime in the same Settings screen as the
baseline modes.

## Why a manual port (no TFLite / ONNX)

Trade-offs considered:

| Runtime | APK size add | Setup | Inference speed | Auditable |
|---------|--------------|-------|-----------------|-----------|
| TensorFlow Lite | ~3 MB | sklearn → ONNX → TF → TFLite chain | µs | ✗ binary blob |
| ONNX Runtime Mobile | ~7 MB | sklearn → ONNX, native lib | µs | ✗ binary blob |
| **Manual JSON + Kotlin walker** | ~5–50 KB (model only) | direct export | ~50 µs | **✓ readable code** |

Choosing **manual port**:
- No new dependency in `build.gradle.kts`
- Model file is plain JSON — version-controllable, diffable, defendable
- Audience can read the Kotlin walker and trust there's no hidden ML magic
- Matches the supervisor's "lightweight model" requirement literally

The notebook (cell 12) already writes the model in a runtime-agnostic
JSON format — Kotlin just needs to load and traverse it.

## Model file format (recap from notebook)

```json
{
  "feature_names":   ["battery_percent", "is_charging", "network_type_rank", ...],
  "classes":         ["CLOUD", "EDGE", "LOCAL"],
  "network_rank":    { "NONE": 0, "LTE": 1, "WIFI": 2, "5G": 3 },
  "task_complexity": { "echo": 0, "sha256": 0, "image-grayscale": 1, ... },
  "trees": [
    {
      "feature":   [3, 0, -2, 7, -2, ...],
      "threshold": [0.45, 30.0, 0.0, 1.5, 0.0, ...],
      "left":      [1, 2, -1, 4, -1, ...],
      "right":     [5, 3, -1, 6, -1, ...],
      "value":     [[0,0,0], [0,0,0], [0.9,0.1,0.0], ...]
    },
    ...
  ]
}
```

- `feature[i] = -2` (or any negative) → leaf node, use `value[i]` for class probs
- `left[i] / right[i] = -1` for leaves
- Non-leaf: descend `left` if `x[feature[i]] <= threshold[i]`, else `right`

A 50-tree forest with depth ≤ 8 typically serialises to **10–50 KB** —
trivial APK overhead. Bundled as `assets/rf-model.json`.

## Android-side architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Settings screen — Execution mode                             │
│   ○ Adaptive (rules)         ← existing default              │
│   ○ Adaptive (ML)            ← NEW — uses RandomForestPolicy │
│   ○ Local-only                                                │
│   ○ Cloud-only                                                │
└────────────────────────┬─────────────────────────────────────┘
                         │ persisted via EndpointsRepository
                         ↓
                  ExecutionMode.kt
            { ADAPTIVE_RULES, ADAPTIVE_ML, LOCAL_ONLY, CLOUD_ONLY }
                         │
                         │ modeProvider()
                         ↓
                  ExecutionProxy.run()
                         │
        ┌────────────────┼──────────────────┐
        │                │                  │
        ↓                ↓                  ↓
    OffloadingPolicy  RandomForestPolicy   forced LOCAL / CLOUD
    (7 named rules)   (50 trees, JSON)
            │                │
            └──────┬─────────┘
                   ↓
              OffloadingPlan
              { target, rule, signals, reasoning }
                   ↓
            offloadingClient → EDGE / CLOUD
                       or
            task.execute()  → LOCAL (with fallback)
```

Both policies emit the same `OffloadingPlan` so the rest of the system
(log entries, CSV recorder, dashboard cards) does not change.

For the ML policy, `rule` is set to one of:
- `ML_PREDICTED_LOCAL` / `ML_PREDICTED_EDGE` / `ML_PREDICTED_CLOUD`

The log's `Why:` line shows the top-3 voted classes and their
probabilities, e.g. `LOCAL 0.78, EDGE 0.20, CLOUD 0.02`.

## New files (when Phase 2.4 is executed)

```
mobile/app/src/main/
├── assets/
│   └── rf-model.json                          ← copied from evaluation/outputs/
└── java/com/thesis/middleware/decision/policy/
    └── RandomForestPolicy.kt                  ← NEW: JSON loader + tree walker
```

## RandomForestPolicy.kt — class skeleton

```kotlin
package com.thesis.middleware.decision.policy

import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.OffloadingPlan
import com.thesis.middleware.decision.SignalSnapshot
import com.thesis.middleware.decision.TaskAnalysis

/**
 * Decision-tree forest policy trained offline (see
 * evaluation/notebooks/random-forest-training.ipynb).
 *
 * Loads the JSON model bundled in `assets/rf-model.json` once at startup,
 * then traverses each tree and majority-votes to predict the target.
 *
 * Same interface as [OffloadingPolicy.evaluate] — the rest of the system
 * doesn't know (or care) which policy is active.
 */
class RandomForestPolicy(modelJson: String) {

    // Parsed once: feature ordering, class labels, lookup maps, trees
    private data class Tree(
        val feature: IntArray,
        val threshold: FloatArray,
        val left: IntArray,
        val right: IntArray,
        val value: Array<FloatArray>,
    )

    private val featureNames: List<String>
    private val classes: List<String>
    private val networkRank: Map<String, Int>
    private val taskComplexityRank: Map<String, Int>
    private val trees: List<Tree>

    init { /* parse JSON … */ }

    fun evaluate(analysis: TaskAnalysis): OffloadingPlan {
        val signals = buildSignalSnapshot(analysis)
        val featureVec = extractFeatures(analysis, signals)
        val (target, probs) = predict(featureVec)
        return OffloadingPlan(
            target = target,
            rule = "ML_PREDICTED_${target.name}",
            reasoning = "RandomForest vote: " + classes.indices.joinToString(", ") {
                "${classes[it]}=${"%.2f".format(probs[it])}"
            },
            signals = signals,
        )
    }

    private fun extractFeatures(a: TaskAnalysis, s: SignalSnapshot): FloatArray { /* … */ }
    private fun predict(x: FloatArray): Pair<ExecutionTarget, FloatArray> { /* … */ }
    private fun traverse(tree: Tree, x: FloatArray): FloatArray { /* … */ }
}
```

(Full implementation deferred until Phase 2.3 model is exported.)

## ExecutionMode update (when Phase 2.4 is executed)

```kotlin
enum class ExecutionMode(val displayName: String) {
    ADAPTIVE_RULES("Adaptive — rule-based (7 rules)"),
    ADAPTIVE_ML("Adaptive — ML (random forest)"),     // ← NEW
    LOCAL_ONLY("Local-only (force phone CPU)"),
    CLOUD_ONLY("Cloud-only (force remote, no fallback)"),
}
```

`ExecutionProxy.run()` branches similarly:

```kotlin
val plan = when (mode) {
    ExecutionMode.ADAPTIVE_RULES -> rulePolicy.evaluate(analysis)
    ExecutionMode.ADAPTIVE_ML    -> mlPolicy.evaluate(analysis)
    ExecutionMode.LOCAL_ONLY     -> /* forced */
    ExecutionMode.CLOUD_ONLY     -> /* forced */
}
```

## Demo angle once deployed

A new comparison scene for the thesis defense:

| Run | Mode | Tap | Expected |
|-----|------|-----|----------|
| 1 | Adaptive — rules | Matrix Math × 3 | Rule 7 fires → EDGE, 280 ms |
| 2 | Adaptive — ML | Matrix Math × 3 | RF predicts EDGE 0.92, 280 ms |
| 3 | Adaptive — rules | Matrix Math × 3 *(battery 25 %)* | Rule 6 fires → EDGE/CLOUD |
| 4 | Adaptive — ML | Matrix Math × 3 *(battery 25 %)* | RF predicts EDGE 0.78 |

If predictions match: ML successfully learned the policy from data.
If they diverge on edge cases: useful discussion point in the thesis
("ML found a pattern rules miss" or "rules encode safety constraints
the data didn't capture").

## Verification checklist (post-deployment)

- [ ] `assets/rf-model.json` size < 100 KB (sanity)
- [ ] App cold-start time not noticeably worse (model parse < 200 ms)
- [ ] `Adaptive — ML` mode renders the new "Why:" line with class probs
- [ ] At least one demo scenario shows ML and rules agreeing
- [ ] At least one demo scenario where ML can be inspected for divergence
- [ ] Log entry for ML decisions carries `rule = ML_PREDICTED_*`
- [ ] CSV `rule` column contains both rule-based ids and ML_PREDICTED_*
      for offline post-analysis

## Effort estimate

| Step | Estimated time |
|------|----------------|
| Implement `RandomForestPolicy.kt` (loader + walker) | ~1.5 h |
| Update `ExecutionMode` + Settings UI radio | ~30 min |
| Branch `ExecutionProxy` to pick policy | ~15 min |
| Wire `MiddlewareApp` to load model + construct policy | ~15 min |
| Update MainActivity rule display names for `ML_PREDICTED_*` | ~15 min |
| End-to-end smoke test | ~30 min |
| **Total** | **~3 h** |

Cheaper than expected because the existing `ExecutionMode` switcher,
log rendering, and CSV pipeline already accept any `OffloadingPlan`
shape — RandomForestPolicy is a drop-in alternative.

## When to execute Phase 2.4

Only **after** the notebook in Phase 2.3 has produced a model with
**test accuracy ≥ 0.85**. Lower than that is not worth deploying — the
ML mode would underperform the rule-based one and weaken the thesis
narrative. If accuracy is low, return to Phase 1 (collect more / better
data) before attempting deployment.
