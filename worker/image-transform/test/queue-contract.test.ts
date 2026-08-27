import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";

import { ContractMismatchError } from "../src/errors";
import { parseTransformRequest } from "../src/queue-contract";

const requestFixture = readFileSync(
  resolve(process.cwd(), "test", "fixtures", "queue", "transform-request-v1.json"),
  "utf8",
);

test("parses the exact v1 transform request golden fixture", () => {
  const request = parseTransformRequest(requestFixture);

  assert.equal(request.contractVersion, 1);
  assert.equal(request.purpose, "REVIEW");
  assert.equal(
    request.originalKey,
    `media/originals/${request.assetId}/original`,
  );
});

test("rejects unknown fields and non-canonical original keys", () => {
  const base = JSON.parse(requestFixture) as Record<string, unknown>;

  assert.throws(
    () => parseTransformRequest(JSON.stringify({ ...base, roles: ["REVIEW_PREVIEW"] })),
    ContractMismatchError,
  );
  assert.throws(
    () =>
      parseTransformRequest(
        JSON.stringify({ ...base, originalKey: "media/originals/another-asset/original" }),
      ),
    ContractMismatchError,
  );
});

test("keeps success and failure golden results free of raw source fields", () => {
  for (const fixture of ["transform-succeeded-v1.json", "transform-failed-v1.json"]) {
    const result = JSON.parse(
      readFileSync(resolve(process.cwd(), "test", "fixtures", "queue", fixture), "utf8"),
    ) as Record<string, unknown>;

    assert.equal("originalKey" in result, false);
    assert.equal("declaredContentType" in result, false);
    assert.equal("sourceUrl" in result, false);
    assert.equal("stackTrace" in result, false);
  }
});
