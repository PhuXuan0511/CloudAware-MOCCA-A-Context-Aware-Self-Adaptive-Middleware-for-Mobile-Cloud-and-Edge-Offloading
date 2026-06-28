"""Generate a PNG image of the Cost Model Symbol Reference table."""
import matplotlib.pyplot as plt
import textwrap


plt.rcParams["font.family"] = "DejaVu Sans"

TITLE = "Cost Model Symbol Reference"
HEADERS = ["Symbol", "Description", "Formula", "Unit"]

ROWS = [
    {
        "symbol": "Time_Local",
        "description": (
            "Wall-clock time for the phone CPU to execute the task locally. "
            "Excludes any network activity."
        ),
        "formula": (
            "Time_Local = Baseline_ms / CpuLoadScore\n"
            "where Baseline_ms ∈ {50 (LIGHT), 300 (MEDIUM), 2000 (HEAVY)} "
            "and CpuLoadScore = 1 − (CpuUsagePercent / 100) "
            "(minimum value 0.05)"
        ),
        "unit": "milliseconds (ms)",
    },
    {
        "symbol": "Time_Remote",
        "description": (
            "Wall-clock time for the remote server to execute the task. "
            "Includes server compute time plus server queue wait. "
            "Excludes network round-trip."
        ),
        "formula": (
            "Time_Remote = Baseline_ms × ServerSpeedupFactor + "
            "ServerQueueWait_ms\n"
            "where ServerSpeedupFactor = 0.3 and ServerQueueWait_ms = 30"
        ),
        "unit": "milliseconds (ms)",
    },
    {
        "symbol": "Energy_Local",
        "description": (
            "Energy consumed by the phone CPU when executing the task "
            "locally. Computed as CPU power multiplied by local execution "
            "time."
        ),
        "formula": (
            "Energy_Local = (CpuActivePower_mW × Time_Local) / 1000\n"
            "where CpuActivePower_mW = 800"
        ),
        "unit": "millijoules (mJ)",
    },
    {
        "symbol": "Energy_Remote",
        "description": (
            "Energy consumed by the phone radio when offloading the task. "
            "Computed as radio transmission power multiplied by transmission "
            "time plus idle wait power multiplied by waiting time."
        ),
        "formula": (
            "Energy_Remote = (RadioTxPower_mW × TransmissionTime_ms + "
            "RadioIdlePower_mW × WaitTime_ms) / 1000\n"
            "where RadioTxPower_mW = 1500, RadioIdlePower_mW = 50, "
            "TransmissionTime_ms = PayloadSize_bytes / Bandwidth_BytesPerMs"
        ),
        "unit": "millijoules (mJ)",
    },
    {
        "symbol": "LocalCost",
        "description": (
            "Composite cost of running the task on the phone. Computed as "
            "the weighted sum of local execution time and local CPU energy."
        ),
        "formula": (
            "LocalCost = LatencyWeight × Time_Local + "
            "EnergyWeight × Energy_Local\n"
            "where LatencyWeight = 0.5 and EnergyWeight = 0.5 "
            "(equal priority)"
        ),
        "unit": "unitless (weighted mixed score)",
    },
    {
        "symbol": "RemoteCost",
        "description": (
            "Composite cost of offloading the task. Computed as the "
            "weighted sum of remote end-to-end latency (including server "
            "compute, network round-trip, and mobility penalty) and "
            "remote radio energy."
        ),
        "formula": (
            "RemoteCost = LatencyWeight × Latency_Remote + "
            "EnergyWeight × Energy_Remote\n"
            "where Latency_Remote = NetworkRoundTrip_ms + "
            "TransmissionTime_ms + Time_Remote + MobilityPenalty_ms"
        ),
        "unit": "unitless (weighted mixed score)",
    },
]


def wrap_preserve_newlines(text: str, width: int) -> str:
    """Wrap text but preserve existing newlines as paragraph breaks."""
    parts = text.split("\n")
    wrapped = [textwrap.fill(p, width=width) for p in parts]
    return "\n".join(wrapped)


def main() -> None:
    desc_width = 38
    formula_width = 55
    unit_width = 22

    table_data = []
    for row in ROWS:
        table_data.append(
            [
                row["symbol"],
                wrap_preserve_newlines(row["description"], desc_width),
                wrap_preserve_newlines(row["formula"], formula_width),
                wrap_preserve_newlines(row["unit"], unit_width),
            ]
        )

    # Estimate figure height based on longest cell line counts
    line_counts = [
        max(len(cell.split("\n")) for cell in row) for row in table_data
    ]
    total_lines = sum(line_counts) + 2  # plus header
    fig_height = max(8, total_lines * 0.42)

    fig, ax = plt.subplots(figsize=(18, fig_height))
    ax.axis("off")
    ax.set_title(TITLE, fontsize=20, fontweight="bold", pad=24)

    table = ax.table(
        cellText=table_data,
        colLabels=HEADERS,
        cellLoc="left",
        loc="center",
        colWidths=[0.10, 0.27, 0.48, 0.15],
    )

    table.auto_set_font_size(False)
    table.set_fontsize(10)

    # Header styling
    for j in range(len(HEADERS)):
        header_cell = table[0, j]
        header_cell.set_facecolor("#1F4E79")
        header_cell.set_text_props(color="white", fontweight="bold", va="center")
        header_cell.set_height(0.05)

    # Row styling with dynamic heights
    for i, row in enumerate(table_data, start=1):
        lines = max(len(cell.split("\n")) for cell in row)
        row_height = 0.040 * lines + 0.012
        for j in range(len(HEADERS)):
            cell = table[i, j]
            cell.set_height(row_height)
            cell.set_text_props(va="top")
            cell.PAD = 0.04
            if i % 2 == 0:
                cell.set_facecolor("#F2F2F2")
            else:
                cell.set_facecolor("#FFFFFF")
            cell.set_edgecolor("#BFBFBF")

    # Symbol column emphasis
    for i in range(1, len(table_data) + 1):
        table[i, 0].set_text_props(fontweight="bold", color="#1F4E79", va="top")

    plt.tight_layout()
    out = "cost_model_table.png"
    plt.savefig(out, dpi=200, bbox_inches="tight", facecolor="white")
    print(f"Saved: {out}")


if __name__ == "__main__":
    main()
