import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import test from "node:test";

const workerRoot = resolve(__dirname, "../..");
const patchedFastUriVersion = "3.1.9";

test("Ajv의 fast-uri 버전은 검증한 보안 수정 버전으로 고정한다", () => {
  const manifest = JSON.parse(readFileSync(resolve(workerRoot, "package.json"), "utf8"));
  const lock = JSON.parse(readFileSync(resolve(workerRoot, "package-lock.json"), "utf8"));

  assert.equal(manifest.overrides.ajv["fast-uri"], patchedFastUriVersion);
  const lockedPackages = Object.entries(lock.packages)
    .filter(([path]) => path.endsWith("node_modules/fast-uri"));
  assert.ok(lockedPackages.length > 0);
  for (const [, entry] of lockedPackages) {
    assert.equal((entry as { version: string }).version, patchedFastUriVersion);
  }
});

test("설치한 Ajv도 보안 수정된 fast-uri를 사용한다", () => {
  const ajvRequire = createRequire(require.resolve("ajv"));

  assert.equal(ajvRequire("fast-uri/package.json").version, patchedFastUriVersion);
});
