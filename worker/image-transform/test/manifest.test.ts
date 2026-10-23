import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

import { ContractMismatchError } from "../src/errors";
import { assertExecutableSpec, EXECUTABLE_SPEC_VERSIONS, loadMediaSpec } from "../src/manifest";

test("v5 preserves v4 image rules and separates the updated native processor", () => {
  const previous = loadMediaSpec(4);
  const current = loadMediaSpec(5);
  assert.equal(previous.digest, "3f292e8387bc18a39cb662f7ba9dff9e76f3c1e686601192c5dc8d2194a2728a");
  assert.equal(current.digest, "448810cc6b305d979f0afd931f05e6efc906132fee7320ec575b71f81d46f18c");
  assert.deepEqual(current.manifest, {
    ...previous.manifest, specVersion: 5, processorRevision: "sharp-webp-v3",
  });
  assert.deepEqual(EXECUTABLE_SPEC_VERSIONS, [5]);
  assert.doesNotThrow(() => assertExecutableSpec(current));
  for (const version of [1, 2, 3, 4]) {
    assert.throws(() => assertExecutableSpec(loadMediaSpec(version)), ContractMismatchError);
  }
  assert.throws(() => loadMediaSpec(6), ContractMismatchError);
});

test("loads the committed v1 manifest with its exact digest", () => {
  const spec = loadMediaSpec(1);

  assert.equal(spec.manifest.specVersion, 1);
  assert.equal(spec.manifest.processorRevision, "sharp-webp-v1");
  assert.equal(
    spec.digest,
    "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f",
  );
  assert.deepEqual(spec.manifest.purposes.REVIEW, ["REVIEW_PREVIEW", "REVIEW_DETAIL"]);
});

test("loads the v2 card-news manifest with its exact digest", () => {
  const spec = loadMediaSpec(2);

  assert.equal(spec.manifest.processorRevision, "sharp-webp-v2");
  assert.equal(
    spec.digest,
    "b8e67084bcdf81ac7fc94951c905726a97ade3783320c67e03c505f5643ddf75",
  );
  assert.deepEqual(spec.manifest.purposes.MAGAZINE_CARD_NEWS, ["MAGAZINE_CARD_NEWS"]);
  assert.deepEqual(spec.manifest.roles.MAGAZINE_CARD_NEWS?.candidates, [
    { width: 432, height: 576 },
    { width: 864, height: 1152 },
    { width: 1296, height: 1728 },
  ]);
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

test("rejects purpose and role sets outside the worker capability contract", () => {
  const directory = mkdtempSync(join(tmpdir(), "hashi-media-spec-"));
  try {
    const committed = loadMediaSpec(1);
    const manifest = JSON.parse(committed.rawBytes.toString("utf8")) as {
      purposes: Record<string, readonly string[]>;
      roles: Record<string, unknown>;
    };
    delete manifest.purposes.REVIEW;
    manifest.roles.FUTURE_ROLE = manifest.roles.PROFILE_AVATAR;
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

test("loads the v3 notice manifest with its exact digest", () => {
  const spec = loadMediaSpec(3);
  assert.equal(spec.digest, "ab130db4caea347d5945531e22c9f2b783a95ad1a9c9fa7b5741a6adcd16b97a");
});
