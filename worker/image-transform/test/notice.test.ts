import assert from "node:assert/strict";
import test from "node:test";
import sharp from "sharp";
import { loadMediaSpec } from "../src/manifest";
import { processImage } from "../src/image-processor";
import { PermanentImageError } from "../src/errors";

test("공지 spec v3는 원본 비율을 유지하고 기존 v1/v2 purpose를 보존한다", async () => {
  const spec = loadMediaSpec(3);
  assert.deepEqual(spec.manifest.purposes.NOTICE, ["NOTICE_DETAIL"]);
  assert.deepEqual(spec.manifest.purposes.MAGAZINE_CARD_NEWS, loadMediaSpec(2).manifest.purposes.MAGAZINE_CARD_NEWS);
  for (const [width, height] of [[2000, 1000], [1000, 2000], [100, 50]]) {
    const bytes = await sharp({create: {width: width!, height: height!, channels: 3, background: "#557799"}}).png().toBuffer();
    const result = await processImage({bytes, declaredByteSize: bytes.length, declaredContentType: "image/png", purpose: "NOTICE", spec});
    for (const rendition of result.renditions) {
      assert.ok(Math.abs(rendition.width / rendition.height - width! / height!) < 0.01);
      assert.ok(rendition.width <= width! && rendition.height <= height!);
    }
  }
});

test("공지 원본은 10MiB까지 허용하고 초과·GIF를 거절한다", async () => {
  const spec = loadMediaSpec(3);
  const bytes = Buffer.alloc(10 * 1024 * 1024);
  bytes.set([0xff, 0xd8, 0xff]);
  await assert.rejects(processImage({bytes, declaredByteSize: bytes.length, declaredContentType: "image/jpeg", purpose: "NOTICE", spec}),
    (error: unknown) => error instanceof PermanentImageError && error.failureCode === "INVALID_IMAGE_DATA");
  const tooLarge = Buffer.alloc(bytes.length + 1);
  await assert.rejects(processImage({bytes: tooLarge, declaredByteSize: tooLarge.length, declaredContentType: "image/jpeg", purpose: "NOTICE", spec}),
    (error: unknown) => error instanceof PermanentImageError && error.failureCode === "SOURCE_FILE_TOO_LARGE");
  const gif = await sharp({create: {width: 1, height: 1, channels: 3, background: "#557799"}}).gif().toBuffer();
  await assert.rejects(processImage({bytes: gif, declaredByteSize: gif.length, declaredContentType: "image/gif", purpose: "NOTICE", spec}),
    (error: unknown) => error instanceof PermanentImageError && error.failureCode === "UNSUPPORTED_IMAGE_TYPE");
});
