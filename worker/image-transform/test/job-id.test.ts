import assert from "node:assert/strict";
import test from "node:test";

import { mediaProcessingJobId } from "../src/job-id";

const ASSET_ID = "a3af06f1-4ef2-46f8-a489-2347fb840447";

test("matches the Java UUIDv5 golden value for the canonical source tuple", () => {
  assert.equal(
    mediaProcessingJobId(ASSET_ID, "version-1", 1),
    "ebb9b9d8-c427-564b-a70e-0fd4e1925e5a",
  );
});

test("changes the job ID when any source tuple component changes", () => {
  const baseline = mediaProcessingJobId(ASSET_ID, "version-1", 1);

  assert.notEqual(
    mediaProcessingJobId("f638de4a-3359-41a3-a899-05b0b68c689e", "version-1", 1),
    baseline,
  );
  assert.notEqual(mediaProcessingJobId(ASSET_ID, "version-2", 1), baseline);
  assert.notEqual(mediaProcessingJobId(ASSET_ID, "version-1", 2), baseline);
});
