import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const trialCounters = {
  cleanupFailure: 'hashi_media_cleanup_failure_total',
  reconciliationFailure: 'hashi_media_reconciliation_failure_total',
  unknown: 'hashi_media_reconciliation_object_total{outcome="unknown"}',
  candidates: 'hashi_media_reconciliation_object_total{outcome="delete"}',
  deleted: 'hashi_media_reconciliation_object_total{outcome="deleted",mode="delete"}',
  cleanupCompleted: 'hashi_media_cleanup_scan_duration_seconds_count{status="completed"}',
  reconciliationCompleted: 'hashi_media_reconciliation_scan_duration_seconds_count{status="completed"}',
  cleanupDispatch: 'hashi_media_cleanup_dispatch_total{outcome="rejected"}',
  reconciliationDispatch: 'hashi_media_reconciliation_dispatch_total{outcome="rejected"}',
};

const fail = () => { throw new Error("DEV alert trial input is invalid."); };
const scoped = (metric) => metric.includes("{")
  ? metric.replace("{", '{job="hashi-server",instance="app:8080",')
  : `${metric}{job="hashi-server",instance="app:8080"}`;
const value = (metric) => `(sum(${scoped(metric)}) or vector(0))`;

// Counter baselines retain the first failure even when a lazily created meter has only one sample.
// A restart invalidates the baselines and must stop the trial, rather than silently resetting them.
export function prepareDevCleanupAlerts(config, now = new Date()) {
  const starts = Math.floor(now.getTime() / 1000);
  const baselineAt = Date.parse(config.baselineAt) / 1000;
  if (config.environment !== "dev" || !Number.isFinite(starts) || !Number.isFinite(baselineAt) ||
      baselineAt > starts || starts - baselineAt > 60 || !Number.isFinite(config.processStartedAt) ||
      config.processStartedAt <= 0 || config.processStartedAt > baselineAt ||
      !Number.isInteger(config.validForMinutes) || config.validForMinutes < 5 || config.validForMinutes > 60 ||
      !Number.isInteger(config.completionGraceMinutes) || config.completionGraceMinutes < 1 ||
      config.completionGraceMinutes >= config.validForMinutes ||
      !["cleanup", "reconciliation"].includes(config.component) ||
      !Number.isInteger(config.candidateLimit) || config.candidateLimit < 0 || config.candidateLimit > 100 ||
      !Number.isInteger(config.deletedLimit) || config.deletedLimit < 0 || config.deletedLimit > 2 ||
      !config.baselines || Object.keys(trialCounters).some((k) => !Number.isFinite(config.baselines[k]) || config.baselines[k] < 0)) fail();
  const expires = starts + config.validForMinutes * 60;
  const delta = (key) => `(${value(trialCounters[key])} - ${config.baselines[key]})`;
  const countKey = config.component === "cleanup" ? "cleanupCompleted" : "reconciliationCompleted";
  const items = [
    ["failure", `(clamp_min(${delta("cleanupFailure")}, 0) + clamp_min(${delta("reconciliationFailure")}, 0)) > bool 0`, "Image cleanup failed"],
    ["unknown", `${delta("unknown")} > bool 0`, "An unrecognized image version needs inspection"],
    ["dispatch", `(clamp_min(${delta("cleanupDispatch")}, 0) + clamp_min(${delta("reconciliationDispatch")}, 0)) > bool 0`, "Image cleanup execution was rejected"],
    ["candidates", `${delta("candidates")} > bool ${config.candidateLimit}`, "Image cleanup candidate observations exceed the trial limit"],
    ["deleted", `${delta("deleted")} > bool ${config.deletedLimit}`, "Image version deletions exceed the trial limit"],
    ["completion", `(${delta(countKey)} < bool 1) and on() (vector(time()) >= ${starts + config.completionGraceMinutes * 60})`, "Image cleanup did not complete within the trial window"],
    ["restart", `((${value("process_start_time_seconds")} != bool ${config.processStartedAt}) + (${value("up")} != bool 1)) > bool 0`, "Trial metrics are unavailable or their baseline changed"],
  ];
  const rules = items.map(([name, expression, summary]) => ({
    uid: `hashi-dev-media-trial-${name}`, title: `DEV media trial ${name}`, condition: "B", for: "0s",
    noDataState: "Alerting", execErrState: "Alerting", isPaused: true,
    labels: { environment: "dev", service: "hashi-media-trial", severity: "warning" },
    annotations: { summary, description: "Stop the DEV trial and inspect its private receipt. Do not enable production cleanup. See docs/media/dev-cleanup-trial.md." },
    data: [
      { refId: "A", relativeTimeRange: { from: 60, to: 0 }, datasourceUid: "prometheus",
        model: { datasource: { type: "prometheus", uid: "prometheus" }, editorMode: "code", instant: true, range: false,
          expr: `((${expression}) and on() (vector(time()) >= ${starts}) and on() (vector(time()) < ${expires})) or vector(0)`, refId: "A", intervalMs: 1000, maxDataPoints: 43200 } },
      { refId: "B", relativeTimeRange: { from: 0, to: 0 }, datasourceUid: "__expr__",
        model: { datasource: { type: "__expr__", uid: "__expr__" }, expression: "$A > 0", type: "math", refId: "B" } },
    ],
  }));
  return { apiVersion: 1, groups: [{ orgId: 1, name: "hashi-dev-media-trial", folder: "Hashi DEV trials", interval: "15s", rules }] };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [configPath, outputPath] = process.argv.slice(2);
    if (!configPath || !outputPath || process.argv.length !== 4) fail();
    const config = JSON.parse(fs.readFileSync(configPath, "utf8").replace(/^\uFEFF/, ""));
    fs.writeFileSync(outputPath, `${JSON.stringify(prepareDevCleanupAlerts(config), null, 2)}\n`, { flag: "wx", mode: 0o600 });
    console.log("Prepared paused DEV Grafana rules; no settings or notification routes were changed.");
  } catch {
    console.error("DEV alert preparation failed. Check the current baseline, limits and a new private output path.");
    process.exitCode = 1;
  }
}
