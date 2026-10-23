import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { renderRuntimeBoundaries, validateRuntimeBoundaryDocument } from "./check-dev-runtime-boundaries.mjs";
import { prepareDevCleanupTrial } from "./prepare-dev-cleanup-trial.mjs";

const now = new Date("2030-01-10T00:00:00.000Z");
const config = { environment: "dev", accountId: "123456789012", region: "ap-northeast-2",
  springRoleName: "hashi-dev-ec2-role", originalBucket: "hashi-dev-media-pipeline-originalimagebucket-example",
  deliveryBucket: "hashi-dev-delivery-example", validForMinutes: 30 };
const boundary = renderRuntimeBoundaries(config.accountId, config.region, config.deliveryBucket).SpringRuntimeBoundary.document;
const asset = "11111111-1111-4111-8111-111111111111";
const manifest = { asset_id: asset, complete: true, content_is_synthetic_text_not_image: true, db_absent_after: true,
  objects: [
    { location: "original", bucket: config.originalBucket, key: `media/originals/${asset}/original`,
      version_id: "originalVersion1", verified: true, last_modified_utc: "2030-01-03T00:00:00.000Z" },
    { location: "rendition", bucket: config.deliveryBucket, key: `media/renditions/${asset}/v1/profile-avatar/128.webp`,
      version_id: "renditionVersion2", verified: true, last_modified_utc: "2030-01-02T00:00:00.000Z" },
  ] };

test("7일을 지난 원본과 파생본의 정확한 version만 만료 시각까지 허용한다", () => {
  const result = prepareDevCleanupTrial(config, manifest, boundary, now);
  assert.deepEqual(result.restoreBoundary, boundary);
  assert.equal(result.expiresAt, "2030-01-10T00:30:00.000Z");
  assert.match(result.originalBoundarySha256, /^[0-9a-f]{64}$/);
  assert.equal(result.grant.Statement.length, 2);
  for (const [index, statement] of result.grant.Statement.entries()) {
    assert.deepEqual(statement.Action, ["s3:DeleteObjectVersion"]);
    assert.equal(statement.Resource, `arn:aws:s3:::${manifest.objects[index].bucket}/${manifest.objects[index].key}`);
    assert.deepEqual(statement.Condition, { StringEquals: { "s3:VersionId": manifest.objects[index].version_id, "s3:ResourceAccount": config.accountId },
      DateGreaterThanEquals: { "aws:CurrentTime": now.toISOString() },
      DateLessThan: { "aws:CurrentTime": result.expiresAt } });
  }
  assert.deepEqual(result.trialBoundary.Statement.slice(0, -2), boundary.Statement);
  assert.throws(() => validateRuntimeBoundaryDocument(result.trialBoundary, boundary), /reviewed DEV ceiling/);
  validateRuntimeBoundaryDocument(result.restoreBoundary, boundary);
});

for (const [key, value] of Object.entries({ environment: "prod", region: "us-east-1", accountId: "*",
  springRoleName: "hashi-prod-ec2-role", originalBucket: "hashi-prod-original",
  deliveryBucket: "hashi-prod-delivery", validForMinutes: 61 })) {
  test(`개발 범위를 벗어난 ${key} 설정을 거부한다`, () => {
    assert.throws(() => prepareDevCleanupTrial({ ...config, [key]: value }, manifest, boundary, now));
  });
}

test("보호기간 전, 불명확한 파일과 추가 대상을 거부한다", () => {
  const mutations = [
    (m) => { m.objects[0].last_modified_utc = "2030-01-03T00:00:00.001Z"; },
    (m) => { m.objects[0].last_modified_utc = "bad"; },
    (m) => { m.objects[0].version_id = "null"; },
    (m) => { m.objects[0].version_id = "*"; },
    (m) => { m.objects[0].version_id = "${aws:username}"; },
    (m) => { m.objects[0].key += "*"; },
    (m) => { m.objects[1].key = `media/renditions/${asset}/v4/../1.webp`; },
    (m) => { m.objects[1].key = `media/renditions/${asset}/v1/profile_avatar/128.webp`; },
    (m) => { m.objects[1].key = `media/renditions/${asset}/v1/unknown-role/128.webp`; },
    (m) => { m.objects[1].key = `media/renditions/${asset}/v4/profile-avatar/128.webp`; },
    (m) => { m.objects[1].key = `media/renditions/${asset}/v1/profile-avatar/256.webp`; },
    (m) => { m.objects[1].key = m.objects[1].key.replace(asset, "22222222-2222-4222-8222-222222222222"); },
    (m) => { m.objects[1].bucket = "hashi-prod-delivery"; },
    (m) => { m.objects[1].verified = false; },
    (m) => { m.objects.push(m.objects[0]); },
    (m) => { m.objects[1] = m.objects[0]; },
    (m) => { m.db_absent_after = false; },
    (m) => { m.complete = false; },
    (m) => { m.content_is_synthetic_text_not_image = false; },
    (m) => { m.asset_id = "*"; },
  ];
  for (const mutate of mutations) {
    const input = structuredClone(manifest);
    mutate(input);
    assert.throws(() => prepareDevCleanupTrial(config, input, boundary, now));
  }
});

test("현재 boundary가 검토된 정책과 다르면 새 권한을 준비하지 않는다", () => {
  const changed = structuredClone(boundary);
  changed.Statement[0].Resource = "*";
  assert.throws(() => prepareDevCleanupTrial(config, manifest, changed, now), /reviewed DEV ceiling/);
  assert.throws(() => prepareDevCleanupTrial({ ...config, validForMinutes: 0 }, manifest, boundary, now));
  assert.throws(() => prepareDevCleanupTrial({ ...config, deliveryBucket: config.originalBucket }, manifest, boundary, now));
});

test("CLI는 실패 단계와 원인을 알리되 비공개 경로나 입력값을 출력하지 않는다", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "hashi-private-trial-"));
  const script = fileURLToPath(new URL("./prepare-dev-cleanup-trial.mjs", import.meta.url));
  const files = ["private-config.json", "private-manifest.json", "private-boundary.json", "private-output.json"]
    .map((name) => path.join(directory, name));
  const oldManifest = structuredClone(manifest);
  oldManifest.objects.forEach((object) => { object.last_modified_utc = "2020-01-01T00:00:00.000Z"; });
  const reset = () => {
    [config, oldManifest, boundary].forEach((value, index) => fs.writeFileSync(files[index], JSON.stringify(value)));
    fs.rmSync(files[3], { force: true });
  };
  const run = (args, expected) => {
    const result = spawnSync(process.execPath, [script, ...args], { encoding: "utf8", windowsHide: true });
    assert.equal(result.error, undefined);
    assert.equal(result.status, 1);
    assert.match(result.stderr, expected);
    assert.doesNotMatch(result.stdout + result.stderr, /private-config|private-manifest|private-boundary|private-output|SECRET_INPUT/);
    assert.ok(!result.stderr.includes(directory));
  };
  try {
    reset();
    run([], /at arguments: expected config/);
    run([path.join(directory, "SECRET_INPUT.json"), ...files.slice(1)], /at config input: file or parent directory not found/);
    for (const [index, stage] of [[0, "config"], [1, "manifest"], [2, "boundary"]]) {
      reset();
      fs.writeFileSync(files[index], '{"SECRET_INPUT": invalid}');
      run(files, new RegExp(`at ${stage} input: invalid JSON`));
    }
    reset();
    fs.writeFileSync(files[0], JSON.stringify({ ...config, environment: "SECRET_INPUT" }));
    run(files, /at scope, age and boundary validation: input does not match/);
    reset();
    const changedBoundary = structuredClone(boundary);
    changedBoundary.Statement[0].Resource = "SECRET_INPUT";
    fs.writeFileSync(files[2], JSON.stringify(changedBoundary));
    run(files, /at scope, age and boundary validation: input does not match/);
    reset();
    fs.writeFileSync(files[3], "SECRET_INPUT");
    run(files, /at output creation: output already exists/);
    assert.equal(fs.readFileSync(files[3], "utf8"), "SECRET_INPUT");
    reset();
    run([...files.slice(0, 3), path.join(directory, "SECRET_INPUT", "output.json")], /at output creation: file or parent directory not found/);
    reset();
    const success = spawnSync(process.execPath, [script, ...files], { encoding: "utf8", windowsHide: true });
    assert.equal(success.status, 0, success.stderr);
    assert.equal(JSON.parse(fs.readFileSync(files[3], "utf8")).grant.Statement.length, 2);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
