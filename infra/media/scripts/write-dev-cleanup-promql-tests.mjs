import fs from "node:fs";
import { prepareDevCleanupAlerts, trialCounters } from "./prepare-dev-cleanup-alerts.mjs";

// Synthetic clock and counters are exclusively for promtool, never an AWS runtime input.
const starts = new Date("1970-01-01T00:02:00Z");
const config = { environment: "dev", baselineAt: starts.toISOString(), processStartedAt: 60,
  validForMinutes: 10, completionGraceMinutes: 2, component: "reconciliation", candidateLimit: 2, deletedLimit: 2,
  baselines: Object.fromEntries(Object.keys(trialCounters).map((k) => [k, 0])) };
const rules = prepareDevCleanupAlerts(config, starts).groups[0].rules;
const expression = (name) => rules.find((r) => r.uid.endsWith(`-${name}`)).data[0].model.expr;
const series = (metric, values) => ({ series: `${metric}{job="hashi-server",instance="app:8080"}`, values });
const healthy = [series("up", "1+0x20"), series("process_start_time_seconds", "60+0x20")];
const check = (name, time, value) => ({ expr: expression(name), eval_time: time,
  exp_samples: [{ labels: "{}", value }] });
const tests = [
  { name: "healthy before completion grace", interval: "1m", input_series: healthy,
    promql_expr_test: [check("failure", "3m", 0), check("restart", "3m", 0), check("completion", "3m", 0)] },
  { name: "first lazy counter sample is not lost", interval: "1m", input_series: [...healthy,
    series("hashi_media_reconciliation_failure_total", "_ _ _ 1")],
    promql_expr_test: [check("failure", "3m", 1)] },
  { name: "unknown and rejected first samples alert", interval: "1m", input_series: [...healthy,
    { series: 'hashi_media_reconciliation_object_total{job="hashi-server",instance="app:8080",outcome="unknown"}', values: "_ _ _ 1" },
    { series: 'hashi_media_cleanup_dispatch_total{job="hashi-server",instance="app:8080",outcome="rejected"}', values: "_ _ _ 1" }],
    promql_expr_test: [check("unknown", "3m", 1), check("dispatch", "3m", 1)] },
  { name: "missing completion and unavailable scrape alert", interval: "1m", input_series: [],
    promql_expr_test: [check("completion", "5m", 1), check("restart", "5m", 1)] },
  { name: "candidate threshold boundary and spike", interval: "1m", input_series: [...healthy,
    { series: 'hashi_media_reconciliation_object_total{job="hashi-server",instance="app:8080",outcome="delete"}', values: "0 0 0 2 3" }],
    promql_expr_test: [check("candidates", "3m", 0), check("candidates", "4m", 1)] },
  { name: "completed scan and exact deletion limit", interval: "1m", input_series: [...healthy,
    { series: 'hashi_media_reconciliation_scan_duration_seconds_count{job="hashi-server",instance="app:8080",status="completed"}', values: "0 0 0 1+0x15" },
    { series: 'hashi_media_reconciliation_object_total{job="hashi-server",instance="app:8080",outcome="deleted",mode="delete"}', values: "0 0 0 2 3" }],
    promql_expr_test: [check("completion", "5m", 0), check("deleted", "3m", 0), check("deleted", "4m", 1)] },
  { name: "restart invalidates baseline", interval: "1m", input_series: [series("up", "1+0x20"), series("process_start_time_seconds", "60 60 60 180")],
    promql_expr_test: [check("restart", "3m", 1)] },
  { name: "another target cannot satisfy completion", interval: "1m", input_series: [...healthy,
    { series: 'hashi_media_reconciliation_scan_duration_seconds_count{job="hashi-prod",instance="prod:8080",status="completed"}', values: "1+0x20" }],
    promql_expr_test: [check("completion", "5m", 1)] },
  { name: "time window expires", interval: "1m", input_series: [],
    promql_expr_test: [check("completion", "12m", 0), check("restart", "12m", 0)] },
];
if (!process.argv[2]) throw new Error("Provide a new temporary output path.");
fs.writeFileSync(process.argv[2], JSON.stringify({ evaluation_interval: "15s", tests }, null, 2), { flag: "wx" });
