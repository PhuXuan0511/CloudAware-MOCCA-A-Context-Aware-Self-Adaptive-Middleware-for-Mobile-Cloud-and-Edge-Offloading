#!/usr/bin/env python3
"""
Three figures for evaluation/EVALUATION.md, generated directly from the real
collected dataset (evaluation/data/training.csv) rather than hand-drawn — kept
as a separate script rather than added to random-forest-training.ipynb because
these support the written evaluation chapter specifically, not the model
training/export workflow the notebook exists for.

    python evaluation/make_evaluation_figures.py

Writes three PNGs to evaluation/outputs/:

  dataset-composition.png   rule counts, adaptive vs. baseline (Section 5.2)
  mobility-routing.png      target tier vs. stationary/moving (Section 5.9)
  network-score-vs-rtt.png  measured RTT vs. network_score, showing the
                            UNSTABLE_NETWORK / good-bandwidth thresholds
                            actually separate the netem-degraded rows
                            (Sections 5.1, 5.9)
"""
from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns

DATA_PATH = Path('evaluation/data/training.csv')
OUTPUT_DIR = Path('evaluation/outputs')

BASELINE_RULES = {'FORCED_LOCAL', 'FORCED_CLOUD'}
UNSTABLE_NETWORK_THRESHOLD = 0.30
GOOD_BANDWIDTH_THRESHOLD = 0.60

sns.set_theme(style='whitegrid')
plt.rcParams['figure.dpi'] = 110


def load() -> pd.DataFrame:
    if not DATA_PATH.exists():
        raise SystemExit(
            f'{DATA_PATH} not found. Run evaluation/collect_data_remote.ps1 '
            '(or collect_data.ps1) first.'
        )
    df = pd.read_csv(DATA_PATH)
    # A clean row's `error` field is an empty CSV cell, which pandas reads as
    # NaN, not ''. `df['error'] == ''` would then be False for every clean
    # row - the opposite of what every filter below actually wants.
    df['error'] = df['error'].fillna('')
    # Exclude the dedicated ADAPTIVE_ML runtime session (rule = ML_PREDICTED_*).
    # Those rows are the RF acting as the live planner; they belong only to the
    # notebook's policy-comparison figures (latency-by-policy, regret-by-policy),
    # where 'ML (RF)' is a first-class policy. The figures built here describe
    # the rule-engine dataset and its cited §5.1/§5.3/§5.5 numbers (network-score
    # validation, energy overrun, mobility routing), which must stay computed on
    # exactly the original rows so those numbers do not shift when ML rows land
    # in training.csv.
    df = df[~df['rule'].astype(str).str.startswith('ML_PREDICTED')].copy()
    df['is_baseline'] = df['rule'].isin(BASELINE_RULES)
    return df


def fig_dataset_composition(df: pd.DataFrame) -> None:
    """Section 5.2 — the rule-count table as a figure, colour-split by
    adaptive vs. forced-baseline rule, so the composition of the dataset is
    visible at a glance rather than read off a table."""
    counts = df['rule'].value_counts().sort_values(ascending=True)
    colors = ['#d95f02' if r in BASELINE_RULES else '#1b7837' for r in counts.index]

    fig, ax = plt.subplots(figsize=(8, 5))
    ax.barh(counts.index, counts.values, color=colors)
    for i, v in enumerate(counts.values):
        ax.text(v + 2, i, str(v), va='center', fontsize=9)

    ax.set_xlabel('rows')
    ax.set_title(f'Dataset composition by triggered rule (n={len(df)})')
    ax.set_xlim(0, counts.max() * 1.15)

    handles = [
        plt.Rectangle((0, 0), 1, 1, color='#1b7837', label='adaptive (rule engine)'),
        plt.Rectangle((0, 0), 1, 1, color='#d95f02', label='forced baseline'),
    ]
    ax.legend(handles=handles, loc='lower right', frameon=True)

    fig.tight_layout()
    fig.savefig(OUTPUT_DIR / 'dataset-composition.png', dpi=160, bbox_inches='tight')
    plt.close(fig)
    print('wrote dataset-composition.png')


def fig_mobility_routing(df: pd.DataFrame) -> None:
    """Section 5.9 — target tier selection split by stationary vs. moving,
    restricted to adaptive (non-baseline) rows. The point this exists to make
    is visual: EDGE selections do not merely drop while moving, they go to
    exactly zero, which is easy to miss in a crosstab table."""
    adaptive = df[~df['is_baseline']].copy()
    adaptive['mobility'] = adaptive['is_stable'].map({True: 'Stationary', False: 'Moving'})

    order = ['LOCAL', 'EDGE', 'CLOUD']
    counts = (
        adaptive.groupby(['mobility', 'target']).size()
        .unstack(fill_value=0).reindex(columns=order)
        .reindex(['Stationary', 'Moving'])
    )

    fig, ax = plt.subplots(figsize=(7, 5))
    x = range(len(counts.index))
    width = 0.25
    palette = {'LOCAL': '#7570b3', 'EDGE': '#1b7837', 'CLOUD': '#d95f02'}

    for i, tier in enumerate(order):
        offset = (i - 1) * width
        bars = ax.bar(
            [p + offset for p in x], counts[tier], width,
            label=tier, color=palette[tier],
        )
        ax.bar_label(bars, padding=2, fontsize=9)

    ax.set_xticks(list(x))
    ax.set_xticklabels(counts.index)
    ax.set_ylabel('rows')
    ax.set_title('Target tier selection by device mobility (adaptive rows only)')
    ax.legend(title='target')

    # The finding this figure exists to show, made explicit rather than left
    # for the reader to notice from bar heights alone. EDGE is the middle bar
    # in each group of 3 (offset (1 - 1) * width = 0), landing at x=1 for the
    # "Moving" group — not at the CLOUD offset.
    edge_x = 1 + (order.index('EDGE') - 1) * width
    ax.annotate(
        'EDGE selections: 0',
        xy=(edge_x, 0), xytext=(edge_x, counts.values.max() * 0.25),
        ha='center', fontsize=10, color='#1b7837', fontweight='bold',
        arrowprops=dict(arrowstyle='->', color='#1b7837'),
    )

    fig.tight_layout()
    fig.savefig(OUTPUT_DIR / 'mobility-routing.png', dpi=160, bbox_inches='tight')
    plt.close(fig)
    print('wrote mobility-routing.png')


def fig_network_score_vs_rtt(df: pd.DataFrame) -> None:
    """Sections 5.1 / 5.9 — does the netem degradation sweep actually show up
    where the policy's thresholds are? Plots every row's measured RTT against
    its computed network_score, with the UNSTABLE_NETWORK (0.30) and
    good-bandwidth (0.60) thresholds drawn in, and UNSTABLE_NETWORK rows
    highlighted. A monotonically decreasing cloud of points crossing 0.30
    exactly where the rule starts firing is the real-data confirmation that
    the injected delay/loss reached the phone's own measurement, not just the
    docker qdisc."""
    plot_df = df[df['network_type'] != 'NONE'].copy()  # offline rows have no RTT signal
    plot_df['is_unstable'] = plot_df['rule'] == 'UNSTABLE_NETWORK'

    # rtt_ms == 0 means "no probe has completed yet" (FeatureExtractor.linkHealth
    # treats it as full health, not zero latency) - a genuine log scale can't
    # plot a literal 0, and the 3 such rows here are not real RTT
    # measurements. Floored to a visually-obvious low value rather than
    # silently dropped, so the plot doesn't quietly lose data points.
    RTT_FLOOR = 3.0
    n_unmeasured = (plot_df['rtt_ms'] <= 0).sum()
    plot_df['rtt_plot'] = plot_df['rtt_ms'].clip(lower=RTT_FLOOR)

    fig, ax = plt.subplots(figsize=(8, 5.5))
    sns.scatterplot(
        data=plot_df, x='rtt_plot', y='network_score',
        hue='is_unstable', palette={False: '#7570b3', True: '#d95f02'},
        alpha=0.6, s=28, ax=ax, legend=False,
    )
    ax.set_xscale('log')

    ax.axhline(UNSTABLE_NETWORK_THRESHOLD, color='#d95f02', ls='--', lw=1.2)
    ax.text(
        RTT_FLOOR * 1.1, UNSTABLE_NETWORK_THRESHOLD + 0.02,
        f'UNSTABLE_NETWORK threshold ({UNSTABLE_NETWORK_THRESHOLD})',
        color='#d95f02', fontsize=9,
    )
    ax.axhline(GOOD_BANDWIDTH_THRESHOLD, color='#1b7837', ls='--', lw=1.2)
    ax.text(
        RTT_FLOOR * 1.1, GOOD_BANDWIDTH_THRESHOLD + 0.02,
        f'good-bandwidth threshold ({GOOD_BANDWIDTH_THRESHOLD})',
        color='#1b7837', fontsize=9,
    )

    handles = [
        plt.Line2D([0], [0], marker='o', color='none', markerfacecolor='#7570b3',
                   alpha=0.6, markersize=8, label='other rules'),
        plt.Line2D([0], [0], marker='o', color='none', markerfacecolor='#d95f02',
                   alpha=0.8, markersize=8, label='UNSTABLE_NETWORK fired'),
    ]
    ax.legend(handles=handles, loc='upper right', frameon=True)

    ax.set_xlabel(f'measured RTT to edge (ms, log scale; {n_unmeasured} unmeasured '
                  f'rows floored to {RTT_FLOOR:.0f}ms)')
    ax.set_ylabel('network_score')
    ax.set_title('Measured RTT vs. computed network score (n=%d, offline rows excluded)' % len(plot_df))

    fig.tight_layout()
    fig.savefig(OUTPUT_DIR / 'network-score-vs-rtt.png', dpi=160, bbox_inches='tight')
    plt.close(fig)
    print('wrote network-score-vs-rtt.png')


def fig_energy_overrun(df: pd.DataFrame) -> None:
    """Section 5.6 — does a long remote round trip cost more whole-device
    energy than the model's LOCAL estimate would have predicted? Restricted to
    remote rows lasting >=500ms (below that, ~1Hz battery-current sampling
    dominates - the same threshold random-forest-training.ipynb's own energy
    cell uses). Ratio > 1 means the phone measurably spent more energy waiting
    on the network than the model says local execution would have cost it."""
    clean = df[(df['error'] == '') & (~df['fell_back']) & (df['actual_ms'] >= 500)].copy()
    remote = clean[clean['target'] != 'LOCAL'].copy()
    remote['overrun_ratio'] = (
        remote['measured_energy_mj'] / remote['est_local_energy_mj'].clip(lower=0.001)
    )
    exceeds = (remote['overrun_ratio'] > 1).sum()

    fig, ax = plt.subplots(figsize=(8, 5.5))
    sns.scatterplot(
        data=remote, x='actual_ms', y='overrun_ratio', hue='task_name',
        style='target', alpha=0.75, s=45, ax=ax,
    )
    ax.set_yscale('log')
    ax.axhline(1.0, color='k', ls='--', lw=1.2)
    ax.text(
        remote['actual_ms'].min(), 1.15,
        'break-even (remote cost == modelled local cost)',
        fontsize=9, color='black',
    )

    ax.set_xlabel('remote round-trip time (ms)')
    ax.set_ylabel('measured energy / modelled local energy (log scale)')
    ax.set_title(
        f'Remote energy overrun vs. round-trip time '
        f'({exceeds}/{len(remote)} rows exceed the local-model estimate)'
    )
    ax.legend(fontsize=8, ncol=2)

    fig.tight_layout()
    fig.savefig(OUTPUT_DIR / 'energy-overrun.png', dpi=160, bbox_inches='tight')
    plt.close(fig)
    print('wrote energy-overrun.png')


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    df = load()
    fig_dataset_composition(df)
    fig_mobility_routing(df)
    fig_network_score_vs_rtt(df)
    fig_energy_overrun(df)
    print(f'\nAll figures written to {OUTPUT_DIR}/')


if __name__ == '__main__':
    main()
