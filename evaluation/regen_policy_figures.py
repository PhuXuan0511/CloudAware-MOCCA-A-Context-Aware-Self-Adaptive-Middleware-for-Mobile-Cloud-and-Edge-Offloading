#!/usr/bin/env python3
"""
Regenerate ONLY the two policy-comparison figures that gain an 'ML (RF)' bar
once the ADAPTIVE_ML session is in training.csv: latency-by-policy.png (notebook
cell 28) and regret-by-policy.png (cell 47). Reproduces those cells verbatim so
the PNGs stay stylistically identical to the rest of the notebook's output;
used because nbconvert is not installed in this environment to re-run the whole
notebook. Also prints the per-policy latency and regret tables for EVALUATION.md.
"""
from __future__ import annotations
from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

DATA_PATH = Path('evaluation/data/training.csv')
OUTPUT_DIR = Path('evaluation/outputs')
sns.set_theme(style='whitegrid')
plt.rcParams['figure.dpi'] = 110

df = pd.read_csv(DATA_PATH)

clean = df[(~df['fell_back']) & (df['error'].isna() | (df['error'].astype(str) == ''))].copy()

def to_tier(row):
    at = str(row['executed_at']).lower()
    return at.upper() if at in ('edge', 'cloud') else 'LOCAL'

clean['tier'] = clean.apply(to_tier, axis=1)
clean['policy'] = clean['rule'].map(
    lambda r: 'Local-only' if r == 'FORCED_LOCAL'
    else 'Cloud-only' if r == 'FORCED_CLOUD'
    else 'ML (RF)' if str(r).startswith('ML_PREDICTED')
    else 'Rule-based'
)
order = [p for p in ['Local-only', 'Cloud-only', 'Rule-based', 'ML (RF)']
         if p in clean['policy'].unique()]

# ---- latency-by-policy.png  (cell 28) ----
fig, axes = plt.subplots(1, 2, figsize=(14, 5))
sns.barplot(data=clean, x='task_name', y='actual_ms', hue='policy',
            hue_order=order, errorbar=('ci', 95), ax=axes[0])
axes[0].set_title('Mean latency with 95% CI')
axes[0].set_ylabel('actual_ms')
axes[0].tick_params(axis='x', rotation=30)
p95 = clean.groupby(['task_name', 'policy'])['actual_ms'].quantile(0.95).reset_index()
sns.barplot(data=p95, x='task_name', y='actual_ms', hue='policy', hue_order=order, ax=axes[1])
axes[1].set_title('p95 latency (tail)')
axes[1].set_ylabel('actual_ms (p95)')
axes[1].tick_params(axis='x', rotation=30)
fig.tight_layout()
fig.savefig(OUTPUT_DIR / 'latency-by-policy.png', dpi=160, bbox_inches='tight')
plt.close(fig)
print('wrote latency-by-policy.png')

def lat(g):
    return pd.Series({'n': len(g), 'mean': g['actual_ms'].mean(),
                      'median': g['actual_ms'].median(), 'p95': g['actual_ms'].quantile(0.95)})
print('\n--- latency by policy ---')
print(clean.groupby('policy').apply(lat, include_groups=False).round(1).to_string())

# ---- regret-by-policy.png  (cell 47) ----
o = clean.copy()
o['net_b'] = pd.cut(o['network_score'], [-0.01, 0.3, 0.6, 1.01], labels=['low', 'mid', 'high'])
o['batt_b'] = pd.cut(o['battery_percent'], [-1, 30, 70, 101], labels=['low', 'mid', 'high'])
o['cpu_b'] = pd.cut(o['cpu_percent'], [-1, 40, 101], labels=['idle', 'busy'])
KEYS = ['task_name', 'net_b', 'batt_b', 'cpu_b']
bt = o.groupby(KEYS + ['tier'], observed=True)['actual_ms'].agg(['mean', 'size']).reset_index()
bt = bt[bt['size'] >= 3]
counts = bt.groupby(KEYS, observed=True)['tier'].nunique()
usable = counts[counts >= 2].index
best = (bt.set_index(KEYS).loc[bt.set_index(KEYS).index.isin(usable)].reset_index()
        .sort_values('mean').groupby(KEYS, observed=True).first()
        .rename(columns={'mean': 'best_ms', 'tier': 'best_tier'})[['best_ms', 'best_tier']])
print(f'\nBuckets with >=2 observed tiers: {len(best)} of {counts.size}')
scored = o.merge(best, left_on=KEYS, right_index=True, how='inner')
scored['regret_ms'] = scored['actual_ms'] - scored['best_ms']
scored['chose_best'] = scored['tier'] == scored['best_tier']

reg = scored.groupby('policy').agg(
    n=('regret_ms', 'size'),
    mean_regret_ms=('regret_ms', 'mean'),
    median_regret_ms=('regret_ms', 'median'),
    p95_regret_ms=('regret_ms', lambda s: s.quantile(0.95)),
    picked_best_tier=('chose_best', 'mean'),
).round(2)
reg['picked_best_tier'] = (100 * reg['picked_best_tier']).round(1)
print('\n--- regret by policy ---')
print(reg.to_string())

fig, ax = plt.subplots(figsize=(8, 4.5))
sns.barplot(data=scored, x='policy', y='regret_ms', errorbar=('ci', 95), ax=ax,
            order=[p for p in order if p in scored['policy'].unique()])
ax.axhline(0, color='k', ls='--', lw=1)
ax.set(ylabel='regret vs matched-condition oracle (ms)',
       title='Lower is better; 0 = matched the best observed tier')
fig.tight_layout()
fig.savefig(OUTPUT_DIR / 'regret-by-policy.png', dpi=160, bbox_inches='tight')
plt.close(fig)
print('\nwrote regret-by-policy.png')
