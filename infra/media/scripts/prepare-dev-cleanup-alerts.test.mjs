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

test("같은 초 안에서 먼저 수집한 기준값을 허용하고 PromQL 시각은 초 단위로 유지한다", () => {
  const current = new Date("2030-01-10T12:00:00.900Z");
  const result = prepareDevCleanupAlerts({ ...config, baselineAt: "2030-01-10T12:00:00.100Z" }, current);
  const starts = Math.floor(current.getTime() / 1000);
  const expires = starts + config.validForMinutes * 60;
  for (const rule of result.groups[0].rules) {
    assert.ok(rule.data[0].model.expr.includes(`vector(time()) >= ${starts})`));
    assert.ok(rule.data[0].model.expr.includes(`vector(time()) < ${expires})`));
  }
  const completion = result.groups[0].rules.find((rule) => rule.uid.endsWith("completion"));
  assert.ok(completion.data[0].model.expr.includes(`vector(time()) >= ${starts + config.completionGraceMinutes * 60})`));
});

test("밀리초까지 현재 시각과 같은 기준값을 허용한다", () => {
  const current = new Date("2030-01-10T12:00:00.900Z");
  assert.doesNotThrow(() => prepareDevCleanupAlerts({ ...config, baselineAt: current.toISOString() }, current));
});

test("현재보다 1밀리초라도 미래인 기준값을 거부한다", () => {
  const current = new Date("2030-01-10T12:00:00.900Z");
  assert.throws(() => prepareDevCleanupAlerts({ ...config, baselineAt: "2030-01-10T12:00:00.901Z" }, current));
});

test("정확히 60초 지난 기준값은 허용한다", () => {
  const current = new Date("2030-01-10T12:00:00.900Z");
  assert.doesNotThrow(() => prepareDevCleanupAlerts({ ...config, baselineAt: "2030-01-10T11:59:00.900Z" }, current));
});

test("60초보다 1밀리초라도 오래된 기준값은 거부한다", () => {
  const current = new Date("2030-01-10T12:00:00.900Z");
  assert.throws(() => prepareDevCleanupAlerts({ ...config, baselineAt: "2030-01-10T11:59:00.899Z" }, current));
});

test("해석할 수 없는 기준 시각과 현재 시각은 거부한다", () => {
  assert.throws(() => prepareDevCleanupAlerts({ ...config, baselineAt: "invalid" }, now));
  assert.throws(() => prepareDevCleanupAlerts(config, new Date("invalid")));
});

test("프로세스 시작 시각은 기존 epoch seconds 단위로 비교한다", () => {
  const current = new Date("2030-01-10T12:00:00.900Z");
  const baselineAt = "2030-01-10T12:00:00.100Z";
  const processStartedAt = Date.parse(baselineAt) / 1000;
  assert.doesNotThrow(() => prepareDevCleanupAlerts({ ...config, baselineAt, processStartedAt }, current));
  assert.throws(() => prepareDevCleanupAlerts({ ...config, baselineAt, processStartedAt: processStartedAt + 0.001 }, current));
});

test("정리와 대조 실행의 완료 기준을 구분하고 과거 누적 실패는 기준값으로 제외한다", () => {
  const result = prepareDevCleanupAlerts({ ...config, component: "cleanup", baselines: { ...config.baselines, cleanupFailure: 7 } }, now);
  assert.match(result.groups[0].rules.find((r) => r.uid.endsWith("completion")).data[0].model.expr, /hashi_media_cleanup_scan_duration_seconds_count/);
  assert.match(result.groups[0].rules.find((r) => r.uid.endsWith("failure")).data[0].model.expr, / - 7/);
});
