import assert from "node:assert/strict";
import test from "node:test";
import sharp from "sharp";
import { loadMediaSpec } from "../src/manifest";
import { processImage } from "../src/image-processor";

test("v4 preserves historical roles and only adds restaurant photo renditions", () => {
  const previous = loadMediaSpec(3).manifest;
  const current = loadMediaSpec(4).manifest;
  for (const [role, spec] of Object.entries(previous.roles)) {
    assert.deepEqual(current.roles[role], spec);
  }
  for (const [purpose, roles] of Object.entries(previous.purposes)) {
    assert.deepEqual(current.purposes[purpose],
      ["RESTAURANT", "RESTAURANT_MENU"].includes(purpose) ? [...roles, "PHOTO_DETAIL"] : roles);
  }
});

for (const purpose of ["RESTAURANT", "RESTAURANT_MENU"]) {
  for (const [width, height] of [[2000, 1000], [1000, 2000], [100, 50]] as const) {
    test(purpose + " PHOTO_DETAIL retains " + width + "x" + height + " without crop or enlargement", async () => {
      const bytes = await sharp({create: {width, height, channels: 3, background: "#557799"}}).png().toBuffer();
      const result = await processImage({bytes, declaredByteSize: bytes.length,
        declaredContentType: "image/png", purpose, spec: loadMediaSpec(4)});
      const photos = result.renditions.filter(value => value.role === "PHOTO_DETAIL");
      assert.ok(photos.length > 0);
      for (const photo of photos) {
        assert.ok(Math.abs(photo.width / photo.height - width / height) < 0.01);
        assert.ok(photo.width <= width && photo.height <= height);
        assert.ok(Math.max(photo.width, photo.height) <= 1296);
      }
    });
  }
}
