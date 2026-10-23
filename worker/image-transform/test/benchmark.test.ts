import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

import sharp from "sharp";

import { loadMediaSpec } from "../src/manifest";

const benchmarkScript = resolve(__dirname, "../../scripts/benchmark.mjs");

for (const [size, expectedStatus, expectedExit] of [
  [2000, "MEASURED", 0],
  [100, "INSUFFICIENT_STANDARD_CANDIDATES", 1],
] as const) {
  test(`측정 도구는 ${size}px 정상 이미지를 v5로 변환하고 후보 부족을 구분한다`, async () => {
    const directory = mkdtempSync(join(tmpdir(), "hashi-private-benchmark-"));
    const input = join(directory, "private-input.png");
    try {
      const bytes = await sharp({
        create: { width: size, height: size, channels: 4, background: "#557799" },
      }).png().toBuffer();
      writeFileSync(input, bytes);

      const execution = spawnSync(process.execPath, [benchmarkScript, input], {
        encoding: "utf8", timeout: 60_000,
      });
      assert.equal(execution.error, undefined);
      assert.equal(execution.status, expectedExit);
      assert.equal(execution.stderr, "");
      assert.equal(execution.stdout.includes(directory), false);
      assert.equal(execution.stdout.includes("private-input.png"), false);
      const result = JSON.parse(execution.stdout) as {
        status: string; specVersion: number; specDigest: string;
        expectedRenditionCount: number; renditionCount: number;
        renditions: Array<{ role: string }>;
      };
      const spec = loadMediaSpec(5);
      const expectedCount = spec.manifest.purposes.RESTAURANT!.reduce(
        (total, role) => total + spec.manifest.roles[role]!.candidates.length, 0,
      );
      assert.equal(result.status, expectedStatus);
      assert.equal(result.specVersion, 5);
      assert.equal(result.specDigest, spec.digest);
      assert.equal(result.expectedRenditionCount, expectedCount);
      assert.equal(expectedCount, 12);
      assert.ok(result.renditions.some(({ role }) => role === "PHOTO_DETAIL"));
      if (expectedStatus === "MEASURED") {
        assert.equal(result.renditionCount, expectedCount);
      } else {
        assert.ok(result.renditionCount < expectedCount);
      }
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

test("측정 실패 로그에 읽지 못한 입력 경로를 노출하지 않는다", () => {
  const directory = mkdtempSync(join(tmpdir(), "hashi-private-benchmark-"));
  try {
    const input = join(directory, "private-missing-input.png");
    const execution = spawnSync(process.execPath, [benchmarkScript, input], {
      encoding: "utf8", timeout: 60_000,
    });
    assert.equal(execution.error, undefined);
    assert.equal(execution.status, 1);
    assert.equal(execution.stdout, "");
    assert.equal(execution.stderr.includes(directory), false);
    assert.equal(execution.stderr.includes("private-missing-input.png"), false);
    assert.equal(JSON.parse(execution.stderr).status, "BENCHMARK_FAILED");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
