import assert from "node:assert/strict";
import test from "node:test";

import { WORKER_RUNTIME } from "../src/runtime";

test("worker runtime is fixed to the v1 Lambda contract", () => {
  assert.deepEqual(WORKER_RUNTIME, {
    architecture: "x86_64",
    node: "nodejs24.x",
    processor: "sharp",
  });
});
