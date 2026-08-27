import assert from "node:assert/strict";
import test from "node:test";

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
