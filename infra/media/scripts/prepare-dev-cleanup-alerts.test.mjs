import assert from "node:assert/strict";
import test from "node:test";
import { prepareDevCleanupAlerts, trialCounters } from "./prepare-dev-cleanup-alerts.mjs";

const now = new Date("2030-01-10T00:00:00Z");
const config = { environment: "dev", baselineAt: now.toISOString(), processStartedAt: now.getTime() / 1000 - 600,
  validForMinutes: 30, completionGraceMinutes: 5, component: "reconciliation", candidateLimit: 2, deletedLimit: 2,
  baselines: Object.fromEntries(Object.keys(trialCounters).map((k) => [k, 0])) };

test("기존 Prometheus를 사용하는 비활성 개발 시험 규칙만 생성한다", () => {
  const result = prepareDevCleanupAlerts(config, now);
  assert.equal(result.groups.length, 1);
  assert.equal(result.groups[0].rules.length, 7);
  for (const rule of result.groups[0].rules) {
    assert.equal(rule.isPaused, true);
    assert.equal(rule.labels.environment, "dev");
    assert.equal(rule.noDataState, "Alerting");
    assert.equal(rule.execErrState, "Alerting");
    assert.equal(rule.data[0].datasourceUid, "prometheus");
    assert.match(rule.data[0].model.expr, /job="hashi-server",instance="app:8080"/);
    assert.match(rule.data[0].model.expr, /vector\(time\(\)\) </);
    assert.doesNotMatch(JSON.stringify(rule), /version_id|asset_id|@|arn:aws|https:/);
  }
  assert.equal(result.contactPoints, undefined);
  assert.equal(result.policies, undefined);
});

test("운영 환경과 오래된 기준값 및 허용 범위를 넘는 삭제 상한을 거부한다", () => {
  for (const modify of [
    (c) => { c.environment = "prod"; },
    (c) => { c.baselineAt = "2020-01-01T00:00:00Z"; },
    (c) => { c.baselineAt = "2031-01-01T00:00:00Z"; },
    (c) => { c.baselines.deleted = -1; },
    (c) => { delete c.baselines.unknown; },
    (c) => { c.deletedLimit = 3; },
    (c) => { c.candidateLimit = 101; },
    (c) => { c.validForMinutes = 61; },
    (c) => { c.completionGraceMinutes = 30; },
    (c) => { c.processStartedAt = 0; },
    (c) => { c.component = "anything"; },
  ]) {
    const input = structuredClone(config);
    modify(input);
    assert.throws(() => prepareDevCleanupAlerts(input, now));
  }
});

test("정리와 대조 실행의 완료 기준을 구분하고 과거 누적 실패는 기준값으로 제외한다", () => {
  const result = prepareDevCleanupAlerts({ ...config, component: "cleanup", baselines: { ...config.baselines, cleanupFailure: 7 } }, now);
  assert.match(result.groups[0].rules.find((r) => r.uid.endsWith("completion")).data[0].model.expr, /hashi_media_cleanup_scan_duration_seconds_count/);
  assert.match(result.groups[0].rules.find((r) => r.uid.endsWith("failure")).data[0].model.expr, / - 7/);
});
