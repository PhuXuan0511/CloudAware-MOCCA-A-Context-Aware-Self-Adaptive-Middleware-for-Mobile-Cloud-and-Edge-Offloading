package com.thesis.middleware.adaptation

/**
 * Runtime execution mode for the offloading middleware.
 *
 * Used to bypass the MAPE-K rule chain for **baseline comparison demos** —
 * the supervisor's evaluation suggestion was to compare adaptive offloading
 * against Local-only and Cloud-only baselines. Switching modes lets the
 * audience watch the same workload run under each strategy live.
 *
 * Persisted via [com.thesis.middleware.settings.EndpointsRepository] and
 * read on every [ExecutionProxy.run] call so changes from the Settings
 * screen take effect immediately without restarting the service.
 *
 * Modes:
 *  - [ADAPTIVE]    — default; full MAPE loop with the 7 named rules.
 *  - [ADAPTIVE_ML] — Random Forest policy; same MAPE Analyze step, but the
 *                    Plan step is replaced by on-device RF inference loaded
 *                    from assets/rf-model.json.
 *  - [LOCAL_ONLY]  — baseline; always executes on the phone, MAPE bypassed.
 *  - [CLOUD_ONLY]  — baseline; always offloads to cloud, NO fallback so
 *                    network failures surface as errors (intentional — shows
 *                    the fragility of a cloud-only design vs MOCCA's
 *                    graceful degradation).
 */
enum class ExecutionMode(val displayName: String) {
    ADAPTIVE("Adaptive (MAPE rule-based)"),
    ADAPTIVE_ML("Adaptive ML (Random Forest)"),
    LOCAL_ONLY("Local-only (force phone CPU)"),
    CLOUD_ONLY("Cloud-only (force remote, no fallback)"),
}
