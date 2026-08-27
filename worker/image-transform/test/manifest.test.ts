import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

import { ContractMismatchError } from "../src/errors";
import { loadMediaSpec } from "../src/manifest";

test("loads the committed v1 manifest with its exact digest", () => {
  const spec = loadMediaSpec(1);

  assert.equal(spec.manifest.specVersion, 1);
  assert.equal(spec.manifest.processorRevision, "sharp-webp-v1");
  assert.equal(
    spec.digest,
    "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32",
  );
  assert.deepEqual(spec.manifest.purposes.REVIEW, ["REVIEW_PREVIEW", "REVIEW_DETAIL"]);
});

test("rejects role candidates that would share one deterministic width key", () => {
  const directory = mkdtempSync(join(tmpdir(), "hashi-media-spec-"));
  try {
    const committed = loadMediaSpec(1);
    const manifest = JSON.parse(committed.rawBytes.toString("utf8")) as {
      roles: Record<string, { candidates: Array<{ width: number; height: number }> }>;
    };
    const candidates = manifest.roles.PROFILE_AVATAR!.candidates;
    candidates[1] = { width: candidates[0]!.width, height: candidates[1]!.height };
    writeFileSync(join(directory, "v1.json"), JSON.stringify(manifest));
    writeFileSync(
      join(directory, "schema.json"),
      readFileSync(resolve(process.cwd(), "../../media-specs/schema.json")),
    );

    assert.throws(() => loadMediaSpec(1, directory), ContractMismatchError);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
