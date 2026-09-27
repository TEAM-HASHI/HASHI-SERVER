import assert from "node:assert/strict";
import test from "node:test";

import { findConflictingRules } from "./check-delivery-lifecycle.mjs";

const expiration = { Days: 30 };
const transition = [{ Days: 30, StorageClass: "GLACIER" }];

test("lifecycle configuration이 없으면 허용한다", () => {
  assert.deepEqual(findConflictingRules({}), []);
});

test("무관한 prefix의 만료 규칙은 허용한다", () => {
  assert.deepEqual(
    findConflictingRules({
      Rules: [
        {
          ID: "legacy-only",
          Status: "Enabled",
          Filter: { Prefix: "legacy/" },
          Expiration: expiration,
        },
      ],
    }),
    [],
  );
});

test("전체 bucket 만료 규칙을 거부한다", () => {
  const conflicts = findConflictingRules({
    Rules: [{ ID: "all", Status: "Enabled", Expiration: expiration }],
  });

  assert.deepEqual(conflicts, [{ id: "all", prefix: "" }]);
});

test("상위 prefix 만료 규칙을 거부한다", () => {
  const conflicts = findConflictingRules({
    Rules: [
      {
        ID: "media",
        Status: "Enabled",
        Filter: { Prefix: "media/" },
        Expiration: expiration,
      },
    ],
  });

  assert.deepEqual(conflicts, [{ id: "media", prefix: "media/" }]);
});

test("rendition 하위 prefix 전환 규칙도 거부한다", () => {
  const conflicts = findConflictingRules({
    Rules: [
      {
        ID: "one-role",
        Status: "Enabled",
        Filter: { And: { Prefix: "media/renditions/restaurant/" } },
        Transitions: transition,
      },
    ],
  });

  assert.deepEqual(conflicts, [
    { id: "one-role", prefix: "media/renditions/restaurant/" },
  ]);
});

test("비활성 규칙은 허용한다", () => {
  assert.deepEqual(
    findConflictingRules({
      Rules: [
        {
          ID: "disabled",
          Status: "Disabled",
          Filter: { Prefix: "media/renditions/" },
          Expiration: expiration,
        },
      ],
    }),
    [],
  );
});

test("tag 조건이 있는 규칙은 태그 없는 rendition과 겹치지 않는다", () => {
  assert.deepEqual(
    findConflictingRules({
      Rules: [
        {
          ID: "temporary-tagged",
          Status: "Enabled",
          Filter: {
            And: {
              Prefix: "media/",
              Tags: [{ Key: "retention", Value: "temporary" }],
            },
          },
          Expiration: expiration,
        },
      ],
    }),
    [],
  );
});

test("delete marker 정리만 하는 규칙은 current rendition을 지우지 않는다", () => {
  assert.deepEqual(
    findConflictingRules({
      Rules: [
        {
          ID: "delete-markers",
          Status: "Enabled",
          Expiration: { ExpiredObjectDeleteMarker: true },
        },
      ],
    }),
    [],
  );
});

test("legacy Prefix 형식의 cold storage 전환도 거부한다", () => {
  const conflicts = findConflictingRules({
    Rules: [
      {
        ID: "legacy-prefix",
        Status: "Enabled",
        Prefix: "media/renditions/",
        Transitions: transition,
      },
    ],
  });

  assert.deepEqual(conflicts, [
    { id: "legacy-prefix", prefix: "media/renditions/" },
  ]);
});
