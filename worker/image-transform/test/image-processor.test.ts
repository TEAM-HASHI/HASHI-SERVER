import assert from "node:assert/strict";
import test from "node:test";

import sharp, { type Metadata } from "sharp";

import { PermanentImageError } from "../src/errors";
import {
  assertImageMetadataWithinLimits,
  IMAGE_LIMITS,
  inspectSource,
  processImage,
  selectRenditionDimensions,
} from "../src/image-processor";
import { loadMediaSpec } from "../src/manifest";
import { createTwoFrameApng } from "./image-fixtures";

const spec = loadMediaSpec(1);

test("creates all standard card candidates in ascending order", async () => {
  const source = await sharp({
    create: {
      width: 500,
      height: 500,
      channels: 3,
      background: "#8f4f32",
    },
  })
    .jpeg({ quality: 90 })
    .toBuffer();

  const result = await processImage({
    bytes: source,
    declaredByteSize: source.length,
    declaredContentType: "image/jpeg",
    purpose: "RESTAURANT",
    spec,
  });

  const card = result.renditions.filter((rendition) => rendition.role === "RESTAURANT_CARD");
  assert.deepEqual(
    card.map(({ width, height }) => ({ width, height })),
    [
      { width: 135, height: 135 },
      { width: 270, height: 270 },
      { width: 405, height: 405 },
    ],
  );
  assert.ok(card.every((rendition) => rendition.format === "WEBP"));
});

test("uses one largest croppable fallback without enlarging a small source", async () => {
  const source = await sharp({
    create: {
      width: 40,
      height: 30,
      channels: 4,
      background: { r: 12, g: 42, b: 84, alpha: 0.4 },
    },
  })
    .png()
    .toBuffer();

  const result = await processImage({
    bytes: source,
    declaredByteSize: source.length,
    declaredContentType: "image/png",
    purpose: "PROFILE",
    spec,
  });

  assert.deepEqual(
    result.renditions.map(({ width, height }) => ({ width, height })),
    [{ width: 30, height: 30 }],
  );
  const metadata = await sharp(result.renditions[0]!.bytes).metadata();
  assert.equal(metadata.hasAlpha, true);
  assert.equal(metadata.space, "srgb");
});

test("applies EXIF orientation and strips source metadata", async () => {
  const source = await sharp({
    create: {
      width: 80,
      height: 40,
      channels: 3,
      background: "#224466",
    },
  })
    .withMetadata({ orientation: 6 })
    .jpeg()
    .toBuffer();

  const inspected = await inspectSource(source, "image/jpeg", source.length);
  assert.equal(inspected.verified.width, 40);
  assert.equal(inspected.verified.height, 80);

  const result = await processImage({
    bytes: source,
    declaredByteSize: source.length,
    declaredContentType: "image/jpeg",
    purpose: "PROFILE",
    spec,
  });
  const outputMetadata = await sharp(result.renditions[0]!.bytes).metadata();
  assert.equal(outputMetadata.orientation, undefined);
  assert.equal(outputMetadata.exif, undefined);
});

test("produces deterministic bytes for an identical source and spec", async () => {
  const source = await sharp({
    create: {
      width: 160,
      height: 160,
      channels: 3,
      background: "#7a91b0",
    },
  })
    .webp({ lossless: true })
    .toBuffer();
  const request = {
    bytes: source,
    declaredByteSize: source.length,
    declaredContentType: "image/webp",
    purpose: "PROFILE",
    spec,
  } as const;

  const first = await processImage(request);
  const second = await processImage(request);

  assert.deepEqual(
    first.renditions.map((rendition) => rendition.checksumSha256),
    second.renditions.map((rendition) => rendition.checksumSha256),
  );
});

test("rejects declared MIME and byte-size mismatches", async () => {
  const source = await sharp({
    create: { width: 8, height: 8, channels: 3, background: "white" },
  })
    .png()
    .toBuffer();

  await assert.rejects(
    inspectSource(source, "image/jpeg", source.length),
    (error: unknown) =>
      error instanceof PermanentImageError && error.failureCode === "SOURCE_MIME_MISMATCH",
  );
  await assert.rejects(
    inspectSource(source, "image/png", source.length + 1),
    (error: unknown) =>
      error instanceof PermanentImageError && error.failureCode === "SOURCE_SIZE_MISMATCH",
  );
});

test("rejects corrupt image payloads after magic-byte inspection", async () => {
  const corruptJpeg = Buffer.from([0xff, 0xd8, 0xff, 0x00, 0x00, 0x00]);

  await assert.rejects(
    inspectSource(corruptJpeg, "image/jpeg", corruptJpeg.length),
    (error: unknown) =>
      error instanceof PermanentImageError && error.failureCode === "INVALID_IMAGE_DATA",
  );
});

test("rejects animated WebP inputs", async () => {
  const frameWidth = 4;
  const frameHeight = 4;
  const firstFrame = Buffer.alloc(frameWidth * frameHeight * 4, 0xff);
  const secondFrame = Buffer.alloc(frameWidth * frameHeight * 4, 0x44);
  const animated = await sharp(Buffer.concat([firstFrame, secondFrame]), {
    animated: true,
    pages: -1,
    raw: {
      width: frameWidth,
      height: frameHeight * 2,
      channels: 4,
      pageHeight: frameHeight,
    },
  })
    .webp({ delay: [100, 100], loop: 0 })
    .toBuffer();

  await assert.rejects(
    inspectSource(animated, "image/webp", animated.length),
    (error: unknown) =>
      error instanceof PermanentImageError &&
      error.failureCode === "ANIMATED_IMAGE_NOT_SUPPORTED",
  );
});

test("rejects APNG inputs", async () => {
  const animated = createTwoFrameApng();

  await assert.rejects(
    inspectSource(animated, "image/png", animated.length),
    (error: unknown) =>
      error instanceof PermanentImageError &&
      error.failureCode === "ANIMATED_IMAGE_NOT_SUPPORTED",
  );
});

test("enforces per-dimension, per-frame and total decode pixel limits", () => {
  assert.throws(
    () =>
      assertImageMetadataWithinLimits({
        width: IMAGE_LIMITS.maxDimension + 1,
        height: 1,
      } as Metadata),
    (error: unknown) =>
      error instanceof PermanentImageError &&
      error.failureCode === "IMAGE_DIMENSION_LIMIT_EXCEEDED",
  );
  assert.throws(
    () =>
      assertImageMetadataWithinLimits({
        width: 8_000,
        height: 8_000,
      } as Metadata),
    (error: unknown) =>
      error instanceof PermanentImageError &&
      error.failureCode === "IMAGE_PIXEL_LIMIT_EXCEEDED",
  );
  assert.throws(
    () =>
      assertImageMetadataWithinLimits({
        width: 5_000,
        height: 5_000,
        pageHeight: 5_000,
        pages: 2,
      } as Metadata),
    (error: unknown) =>
      error instanceof PermanentImageError &&
      error.failureCode === "IMAGE_PIXEL_LIMIT_EXCEEDED",
  );
});

test("selects the exact half-up fallback for a portrait role", () => {
  const role = spec.manifest.roles.REVIEW_DETAIL!;
  assert.deepEqual(selectRenditionDimensions(100, 100, role), [{ width: 68, height: 99 }]);
});
