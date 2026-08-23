#!/usr/bin/env python3
"""
Quick verification + numbers for the ADAPTIVE_ML session, run against
training_with_ml.csv straight after collect_data_ml.ps1 / the driven session,
BEFORE promoting anything to training.csv. Reproduces the notebook's own policy
mapping (cell 10) and matched-condition regret oracle (cell 15) so the "ML (RF)"
latency and regret rows can be sanity-checked without a full notebook run.

    python evaluation/analyze_ml_session.py

Prints: ML row count, tier + rule distribution, per-policy latency stats, and
per-policy regret against the matched-condition oracle (oracle rebuilt from ALL
rows including the new ML ones, exactly as the notebook does).
"""
from __future__ import annotations
from pathlib import Path
import pandas as pd

SRC = Path('evaluation/data/training_with_ml.csv')


def to_tier(row):
    at = str(row['executed_at']).lower()
    return at.upper() if at in ('edge', 'cloud') else 'LOCAL'


def policy(rule):
    r = str(rule)
    if r == 'FORCED_LOCAL':
        return 'Local-only'
    if r == 'FORCED_CLOUD':
        return 'Cloud-only'
    if r.startswith('ML_PREDICTED'):
        return 'ML (RF)'
    return 'Rule-based'


def main():
    if not SRC.exists():
        raise SystemExit(f'{SRC} not found — run the ADAPTIVE_ML session first.')
    df = pd.read_csv(SRC)
    df['error'] = df['error'].fillna('')
    n_ml = df['rule'].astype(str).str.startswith('ML_PREDICTED').sum()
    print(f'Total rows: {len(df)}   ML_PREDICTED rows: {n_ml}\n')

    ml = df[df['rule'].astype(str).str.startswith('ML_PREDICTED')].copy()
    print('--- ML rule distribution ---')
    print(ml['rule'].value_counts().to_string(), '\n')
    print('--- ML target distribution ---')
    print(ml['target'].value_counts().to_string(), '\n')
    print('--- ML fallbacks / errors (should be ~0) ---')
    print(f"fell_back: {int(ml['fell_back'].sum())}   "
          f"non-empty error: {int((ml['error'] != '').sum())}\n")

    clean = df[(~df['fell_back']) & (df['error'] == '')].copy()
    clean['tier'] = clean.apply(to_tier, axis=1)
    clean['policy'] = clean['rule'].map(policy)

    print('--- clean rows: policy x tier ---')
    print(clean.groupby(['policy', 'tier']).size().unstack(fill_value=0).to_string(), '\n')

    def lat(g):
        return pd.Series({'n': len(g), 'mean': g['actual_ms'].mean(),
                          'median': g['actual_ms'].median(),
                          'p95': g['actual_ms'].quantile(0.95)})
    print('--- latency by policy (ms) ---')
    print(clean.groupby('policy').apply(lat, include_groups=False).round(1).to_string(), '\n')

    # matched-condition oracle (notebook cell 15), oracle rebuilt from all rows
    o = clean.copy()
    o['net_b'] = pd.cut(o['network_score'], [-0.01, 0.3, 0.6, 1.01], labels=['poor', 'fair', 'good'])
    o['batt_b'] = pd.cut(o['battery_percent'], [-1, 30, 70, 101], labels=['low', 'mid', 'high'])
    o['cpu_b'] = pd.cut(o['cpu_percent'], [-1, 40, 101], labels=['idle', 'busy'])
    KEYS = ['task_name', 'net_b', 'batt_b', 'cpu_b']
    bt = o.groupby(KEYS + ['tier'], observed=True)['actual_ms'].agg(['size', 'mean']).reset_index()
    bt = bt[bt['size'] >= 3]
    usable = bt.groupby(KEYS, observed=True)['tier'].nunique()
    usable = usable[usable >= 2].index
    best = (bt.set_index(KEYS).loc[bt.set_index(KEYS).index.isin(usable)]
            .reset_index().sort_values('mean').groupby(KEYS, observed=True)
            .first().rename(columns={'mean': 'best_ms'})[['best_ms']])
    scored = o.merge(best, left_on=KEYS, right_index=True, how='inner')
    scored['regret_ms'] = scored['actual_ms'] - scored['best_ms']
    # best-tier hit: did this row's tier match the bucket's best tier?
    best_tier = (bt.set_index(KEYS).loc[bt.set_index(KEYS).index.isin(usable)]
                 .reset_index().sort_values('mean').groupby(KEYS, observed=True)
                 .first()[['tier']].rename(columns={'tier': 'best_tier'}))
    scored = scored.merge(best_tier, left_on=KEYS, right_index=True, how='left')
    scored['is_best'] = scored['tier'] == scored['best_tier']

    print(f'--- regret vs matched-condition oracle ({len(usable)} qualifying buckets) ---')
    reg = scored.groupby('policy').agg(
        n=('regret_ms', 'size'),
        median_regret=('regret_ms', 'median'),
        best_tier_pct=('is_best', lambda s: 100 * s.mean()),
    ).round(2)
    print(reg.to_string())


if __name__ == '__main__':
    main()
