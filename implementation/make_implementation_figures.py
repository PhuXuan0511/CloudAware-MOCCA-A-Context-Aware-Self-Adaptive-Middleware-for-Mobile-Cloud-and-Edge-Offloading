#!/usr/bin/env python3
"""
Three schematic diagrams for implementation/IMPLEMENTATION.md, drawn directly
from the real class/method structure (MapeLoop.kt, OffloadingPolicy.kt,
ConnectionManager.kt, OffloadingClient.kt, edge-server/, cloud-server/) rather
than a generic textbook MAPE-K picture — every label and threshold here names
an actual class, constant, or condition in the codebase.

    python implementation/make_implementation_figures.py

Writes three PNGs to implementation/outputs/:

  system-architecture.png   device <-> edge/cloud containers, module boundaries
  mapek-cycle.png           Monitor/Analyze/Plan/Execute/Knowledge -> real classes
  rule-priority-chain.png   OffloadingPolicy's 7-rule evaluation order
"""
from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Polygon

OUTPUT_DIR = Path('implementation/outputs')

NAVY = '#1f3a5f'
STEEL = '#3b6ea5'
GREEN = '#1b7837'
ORANGE = '#d95f02'
PURPLE = '#7570b3'
GREY = '#555555'
LOCAL_COLOR = '#7570b3'
EDGE_COLOR = '#1b7837'
CLOUD_COLOR = '#d95f02'


def box(ax, xy, w, h, text, fc='white', ec=NAVY, fontsize=9.5, weight='normal', lw=1.4, zorder=3):
    x, y = xy
    patch = FancyBboxPatch(
        (x, y), w, h,
        boxstyle='round,pad=0.02,rounding_size=0.08',
        linewidth=lw, edgecolor=ec, facecolor=fc, zorder=zorder,
    )
    ax.add_patch(patch)
    # Text is always drawn in the (dark) edge color, never white-on-light,
    # since every box here uses a white or pale-tint fill - a solid dark fc
    # is never used, so there is no case needing light-colored text.
    ax.text(x + w / 2, y + h / 2, text, ha='center', va='center',
             fontsize=fontsize, color=ec,
             fontweight=weight, zorder=zorder + 1, linespacing=1.35)
    return patch


def arrow(ax, p1, p2, color=GREY, style='-|>', lw=1.4, ls='-', connectionstyle='arc3,rad=0.0', zorder=2):
    a = FancyArrowPatch(
        p1, p2, arrowstyle=style, mutation_scale=14, linewidth=lw,
        color=color, linestyle=ls, connectionstyle=connectionstyle, zorder=zorder,
    )
    ax.add_patch(a)
    return a


def fig_system_architecture() -> None:
    """Device-side module boundaries against the two server containers, the
    single bridge network, and the edge's own overload-forwarding path to
    cloud — the concrete instantiation of the three-tier testbed the
    evaluation chapter measures."""
    fig, ax = plt.subplots(figsize=(13, 8))
    ax.set_xlim(0, 132)
    ax.set_ylim(0, 78)
    ax.set_axis_off()

    # ---- Android device ----
    box(ax, (2, 4), 46, 70, '', fc='#eef3fa', ec=NAVY, lw=2, zorder=1)
    ax.text(25, 70, 'Android Device (Samsung Galaxy A56)', ha='center', fontsize=11,
            fontweight='bold', color=NAVY)

    box(ax, (5, 61), 40, 7, 'ContextManager + FeatureExtractor\n(poll every 2000ms -> 4 normalized scores)',
        fc='white', ec=STEEL, fontsize=8.6)
    box(ax, (5, 50), 40, 8,
        'MapeLoop  (Monitor -> Analyze -> Plan -> Execute)\nLatency / Energy / ExecutionTime Estimators',
        fc='white', ec=STEEL, fontsize=8.3)
    box(ax, (5, 40), 18.5, 7, 'OffloadingPolicy\n(7 rules, priority order)', fc='white', ec=GREEN, fontsize=8.6)
    box(ax, (26.5, 40), 18.5, 7, 'RandomForestPolicy\n(12-feature tree walk)', fc='white', ec=GREEN, fontsize=8.6)
    ax.text(25, 38.2, 'Mode switch: ADAPTIVE | ADAPTIVE_ML | LOCAL_ONLY | CLOUD_ONLY',
            ha='center', fontsize=7, color=GREY, style='italic')
    box(ax, (5, 27), 18.5, 7, 'ExecutionProxy\n(dispatch, 10s timeout,\nfallback-to-LOCAL)', fc='white', ec=STEEL, fontsize=8.3)
    box(ax, (26.5, 27), 18.5, 7, 'ConnectionManager\n(/health probe, 5s TTL cache)', fc='white', ec=STEEL, fontsize=8.3)
    box(ax, (5, 16), 40, 6, 'OffloadingClient\nHTTP POST /api/v1/offload (Base64-MIME wire format)',
        fc='white', ec=STEEL, fontsize=8.3)
    box(ax, (5, 8), 40, 4.5, 'MetricsRecorder -> mocca-metrics.csv (28 columns)', fc='white', ec=GREY, fontsize=8.3)

    arrow(ax, (25, 61), (25, 58), color=STEEL)
    arrow(ax, (25, 50), (25, 47), color=STEEL)
    arrow(ax, (14, 40), (14, 34), color=GREEN)
    arrow(ax, (35.5, 40), (35.5, 34), color=GREEN)
    arrow(ax, (14, 27), (14, 22), color=STEEL)
    arrow(ax, (25, 16), (25, 12.5), color=GREY, ls=':')

    # ---- Edge server container ----
    box(ax, (58, 10, ), 32, 60, '', fc='#eefaf0', ec=GREEN, lw=2, zorder=1)
    ax.text(72, 73, 'Edge Server  (2 vCPU / 2 GB)',
            ha='center', fontsize=9, fontweight='bold', color=GREEN)
    box(ax, (61, 58), 26, 7, 'FastAPI: /health, /api/v1/offload,\n/api/v1/status, /api/v1/queue', fc='white', ec=GREEN, fontsize=8.3)
    box(ax, (61, 48), 26, 7, 'ResourceMonitor\n(cgroup CPU/mem, is_overloaded())', fc='white', ec=GREEN, fontsize=8.3)
    box(ax, (61, 38), 26, 7, 'TaskExecutor (semaphore=4)\n+ OffloadingBroker', fc='white', ec=GREEN, fontsize=8.3)
    arrow(ax, (74, 58), (74, 55), color=GREEN)
    arrow(ax, (74, 48), (74, 45), color=GREEN)

    # ---- Cloud server container ----
    box(ax, (96, 10), 32, 60, '', fc='#fdf3ec', ec=ORANGE, lw=2, zorder=1)
    ax.text(114, 73, 'Cloud Server  (4 vCPU / 4 GB)',
            ha='center', fontsize=9, fontweight='bold', color=ORANGE)
    box(ax, (99, 58), 26, 7, 'FastAPI: /health, /api/v1/offload,\n/api/v1/status', fc='white', ec=ORANGE, fontsize=8.3)
    box(ax, (99, 38), 26, 7, 'TaskExecutor (no concurrency cap)', fc='white', ec=ORANGE, fontsize=8.3)
    arrow(ax, (112, 58), (112, 45), color=ORANGE)

    # shared registry, spanning both containers
    box(ax, (61, 27), 64, 6,
        'shared/tasks/registry.py\necho, sha256, image-grayscale, matrix-multiply, video-frame-edges (imported by both)',
        fc='white', ec=GREY, fontsize=7.8)

    # device -> edge HTTP (straight; stays right of every device-internal box)
    arrow(ax, (48, 19), (61, 61.5), color=STEEL)
    ax.text(49.5, 34, 'HTTP POST\n/api/v1/offload', fontsize=7.8, color=STEEL, ha='right', linespacing=1.2)

    # edge -> cloud forwarding (short, horizontal, same height on both sides)
    arrow(ax, (87, 41.5), (99, 41.5), color=ORANGE, lw=2)
    ax.text(93, 44.3, 'forward_to_cloud()\nif CPU>85% or mem>80%', fontsize=7.8, color=ORANGE,
            ha='center', linespacing=1.2, fontweight='bold')

    ax.text(25, 6, 'OffloadingClient also POSTs directly to the Cloud endpoint when target == CLOUD (bypassing Edge)',
            ha='center', fontsize=7, color=GREY, style='italic')

    ax.text(112, 15, 'single Docker bridge network: middleware-net', ha='center',
            fontsize=8, color=GREY, style='italic')
    ax.plot([59, 126], [19, 19], color=GREY, lw=1, ls=':')

    ax.set_title('Figure 1 — System architecture: device modules, container boundaries, edge-to-cloud forwarding',
                  fontsize=11, pad=14)
    fig.tight_layout()
    fig.savefig(OUTPUT_DIR / 'system-architecture.png', dpi=160, bbox_inches='tight')
    plt.close(fig)
    print('wrote system-architecture.png')


def fig_mapek_cycle() -> None:
    """The five MAPE-K phases mapped onto the concrete classes/methods that
    implement them, plus the two independent triggers (per-task synchronous
    decide(), and the periodic drift check) that the textbook loop diagram
    never distinguishes."""
    fig, ax = plt.subplots(figsize=(12.5, 7.5))
    ax.set_xlim(0, 120)
    ax.set_ylim(0, 74)
    ax.set_axis_off()

    # triggers, above the main row
    box(ax, (2, 60), 40, 10,
        'Task arrival\nsynchronous MapeLoop.decide()\nper OffloadableTask', fc='#eef3fa', ec=STEEL, fontsize=8.6)
    box(ax, (78, 60), 40, 10,
        'Context drift timer\nevery 3000ms over a 5000ms window;\nfires if max score delta >= 0.2', fc='#eef3fa', ec=STEEL, fontsize=8.6)
    ax.text(60, 58, '(drift check emits ContextDriftEvent; it does not itself force a re-decision)',
            ha='center', fontsize=7.5, color=GREY, style='italic')

    # main row: Monitor -> Analyze -> Plan -> Execute
    row_y, row_h = 38, 14
    cols = [
        ('MONITOR', 'ContextManager (2000ms poll)\nFeatureExtractor -> 4 scores', STEEL, 2),
        ('ANALYZE', 'LatencyEstimator\nEnergyEstimator\nExecutionTimeEstimator', PURPLE, 30),
        ('PLAN', 'OffloadingPolicy (7 rules)\nor RandomForestPolicy\n(mode switch)', GREEN, 58),
        ('EXECUTE', 'ExecutionProxy\n10s timeout,\nfallback-to-LOCAL', ORANGE, 86),
    ]
    col_w = 24
    for title, detail, c, x in cols:
        box(ax, (x, row_y), col_w, row_h, f'{title}\n{detail}', ec=c, fontsize=8.7, weight='bold')
    for _, _, _, x in cols[:-1]:
        arrow(ax, (x + col_w, row_y + row_h / 2), (x + col_w + 6, row_y + row_h / 2), color=NAVY, lw=2)

    # triggers feed into Monitor
    arrow(ax, (22, 60), (15, row_y + row_h), color=STEEL)
    arrow(ax, (98, 60), (25, row_y + row_h), color=STEEL, ls='--', connectionstyle='arc3,rad=0.2')

    # Knowledge, below, spanning under Analyze/Plan
    kx, ky, kw, kh = 30, 12, 56, 14
    box(ax, (kx, ky), kw, kh, 'KNOWLEDGE\nContextHistoryStore\nMetricsRecorder (28-col CSV)',
        ec=GREY, fontsize=8.7, weight='bold')

    # Execute -> Knowledge (down)
    arrow(ax, (86 + col_w / 2, row_y), (86 + col_w / 2, ky + kh), color=NAVY, lw=1.8,
          connectionstyle='arc3,rad=0.35')
    # Knowledge -> Monitor (return arrow, bowed left, closing the cycle)
    arrow(ax, (kx, ky + kh / 2), (2 + col_w / 2, row_y), color=NAVY, lw=1.8, ls='--',
          connectionstyle='arc3,rad=0.35')
    ax.text(16, 24, 'feeds next\nMonitor cycle', ha='center', fontsize=7.8, color=NAVY,
            style='italic', linespacing=1.2)

    ax.set_title('Figure 2 — The MAPE-K adaptation cycle mapped onto MapeLoop.kt',
                  fontsize=11, pad=14)
    fig.tight_layout()
    fig.savefig(OUTPUT_DIR / 'mapek-cycle.png', dpi=160, bbox_inches='tight')
    plt.close(fig)
    print('wrote mapek-cycle.png')


def fig_rule_priority_chain() -> None:
    """OffloadingPolicy.evaluate()'s exact rule order: first non-null match
    wins. Drawn as a flowchart rather than left as a table, since the
    short-circuit priority order (not just the rule set) is the part a table
    tends to obscure."""
    fig, ax = plt.subplots(figsize=(11.5, 11))
    ax.set_xlim(0, 110)
    ax.set_ylim(0, 108)
    ax.set_axis_off()

    rules = [
        ('OFFLINE', '!network.isOnline', 'LOCAL'),
        ('UNSTABLE_NETWORK', 'network_score < 0.30', 'LOCAL'),
        ('COMPUTE_FLOOR_NOT_MET', 'localMs / remoteMs < 1.5x', 'LOCAL'),
        ('LATENCY_SENSITIVE', 'complexity == LIGHT', 'pickRemoteTarget()'),
        ('LOW_BATTERY_OFFLOAD', 'battery<30%, not charging,\nremoteEnergy < localEnergy', 'pickRemoteTarget()'),
        ('HEAVY_COMPUTE_GOOD_BANDWIDTH', 'complexity == HEAVY,\nnetwork_score >= 0.60', 'pickRemoteTarget()'),
        ('BALANCED_COST (default)', 'localCost <= remoteCost x 1.05 ?', 'LOCAL  or  pickRemoteTarget()'),
    ]

    top = 100
    step = 13.5
    diamond_w, diamond_h = 34, 9.5
    dx = 20

    for i, (name, cond, action) in enumerate(rules):
        y = top - i * step
        cx = dx + diamond_w / 2
        pts = [(cx - diamond_w / 2, y), (cx, y + diamond_h / 2),
               (cx + diamond_w / 2, y), (cx, y - diamond_h / 2)]
        ax.add_patch(Polygon(pts, closed=True, facecolor='white', edgecolor=NAVY, linewidth=1.4, zorder=3))
        ax.text(cx, y + 2.3, name, ha='center', va='center', fontsize=8.4, fontweight='bold', color=NAVY, zorder=4)
        ax.text(cx, y - 2.3, cond, ha='center', va='center', fontsize=7.3, color=GREY, zorder=4, linespacing=1.2)

        # "yes" branch -> action box on the right
        action_x = dx + diamond_w + 22
        ac = LOCAL_COLOR if action == 'LOCAL' else (STEEL if 'or' in action else GREEN)
        box(ax, (action_x, y - 4), 26, 8, action, ec=ac, fontsize=8.3, weight='bold')
        arrow(ax, (cx + diamond_w / 2, y), (action_x, y), color=ac, lw=1.6)
        ax.text(cx + diamond_w / 2 + 3, y + 1.8, 'yes', fontsize=7.5, color=ac, fontweight='bold')

        # "no" branch -> down to next rule
        if i < len(rules) - 1:
            arrow(ax, (cx, y - diamond_h / 2), (cx, y - step + diamond_h / 2), color=GREY, lw=1.4)
            ax.text(cx + 2, y - step / 2, 'no', fontsize=7.5, color=GREY)

    # pickRemoteTarget detail box, bottom — the 4 boxes above already repeat
    # its name; this states the shared definition once rather than drawing
    # four crossing connector lines back up to each one.
    py = top - len(rules) * step + 2
    box(ax, (dx - 2, py - 14), 74, 11,
        'pickRemoteTarget(): stationary AND network_score >= 0.60  ->  EDGE   |   otherwise  ->  CLOUD',
        ec=GREEN, fontsize=8.6, weight='bold')

    ax.text(55, 106, 'Figure 3 — OffloadingPolicy rule-priority chain (first match wins)',
            ha='center', fontsize=11, fontweight='bold', color=NAVY)
    ax.text(55, 2, 'Two additional pseudo-rules bypass this chain entirely for baseline modes: '
                    'FORCED_LOCAL and FORCED_CLOUD (ExecutionMode.LOCAL_ONLY / CLOUD_ONLY).',
            ha='center', fontsize=8, color=GREY, style='italic')

    fig.tight_layout()
    fig.savefig(OUTPUT_DIR / 'rule-priority-chain.png', dpi=160, bbox_inches='tight')
    plt.close(fig)
    print('wrote rule-priority-chain.png')


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    fig_system_architecture()
    fig_mapek_cycle()
    fig_rule_priority_chain()
    print(f'\nAll figures written to {OUTPUT_DIR}/')


if __name__ == '__main__':
    main()
