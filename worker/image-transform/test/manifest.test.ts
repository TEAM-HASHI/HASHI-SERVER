import assert from "node:assert/strict";
import test from "node:test";

import { loadMediaSpec } from "../src/manifest";

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
