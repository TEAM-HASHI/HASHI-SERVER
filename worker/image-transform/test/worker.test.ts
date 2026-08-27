import assert from "node:assert/strict";
import test from "node:test";

import sharp from "sharp";

import { ContractMismatchError } from "../src/errors";
import { processImage } from "../src/image-processor";
import type {
  ImageObjectStorage,
  OriginalObject,
  ReadOriginalRequest,
  TransformResultPublisher,
  WriteRenditionRequest,
} from "../src/ports";
import type { TransformResult } from "../src/queue-contract";
import { ImageTransformWorker } from "../src/worker";

const ASSET_ID = "a3af06f1-4ef2-46f8-a489-2347fb840447";
const JOB_ID = "f57dbf16-f7ca-46ec-8d80-8142be93d12a";
const SPEC_DIGEST = "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32";
const VERSION_ID = "3Lg-source-version";
const ETAG = '"etag-value"';

test("reads the fixed source, writes deterministic renditions and publishes success", async () => {
  const source = await jpegSource(100, 100);
  const storage = new FakeStorage(original(source));
  const publisher = new FakePublisher();
  const worker = new ImageTransformWorker(storage, publisher);

  await worker.processMessage(requestBody(source));

  assert.deepEqual(storage.readRequests, [
    {
      objectKey: `media/originals/${ASSET_ID}/original`,
      sourceETag: ETAG,
      sourceVersionId: VERSION_ID,
    },
  ]);
  assert.deepEqual(
    storage.writes.map((write) => write.objectKey),
    [
      `media/renditions/${ASSET_ID}/v1/profile-avatar/48.webp`,
      `media/renditions/${ASSET_ID}/v1/profile-avatar/96.webp`,
    ],
  );
  assert.ok(storage.writes.every((write) => write.jobId === JOB_ID));
  assert.equal(publisher.results.length, 1);
  const result = publisher.results[0]!;
  assert.equal(result.status, "SUCCEEDED");
  if (result.status === "SUCCEEDED") {
    assert.deepEqual(
      result.renditions.map(({ role, width, height, objectKey }) => ({
        role,
        width,
        height,
        objectKey,
      })),
      [
        {
          role: "PROFILE_AVATAR",
          width: 48,
          height: 48,
          objectKey: `media/renditions/${ASSET_ID}/v1/profile-avatar/48.webp`,
        },
        {
          role: "PROFILE_AVATAR",
          width: 96,
          height: 96,
          objectKey: `media/renditions/${ASSET_ID}/v1/profile-avatar/96.webp`,
        },
      ],
    );
  }
});

test("publishes a sanitized permanent failure without writing renditions", async () => {
  const corrupt = Buffer.from([0xff, 0xd8, 0xff, 0x00, 0x00, 0x00]);
  const storage = new FakeStorage(original(corrupt));
  const publisher = new FakePublisher();
  const worker = new ImageTransformWorker(storage, publisher);

  await worker.processMessage(requestBody(corrupt));

  assert.equal(storage.writes.length, 0);
  assert.deepEqual(publisher.results, [
    {
      contractVersion: 1,
      jobId: JOB_ID,
      assetId: ASSET_ID,
      specVersion: 1,
      specDigest: SPEC_DIGEST,
      sourceVersionId: VERSION_ID,
      sourceETag: ETAG,
      status: "FAILED",
      failureCode: "INVALID_IMAGE_DATA",
    },
  ]);
});

test("retries contract mismatches instead of publishing user failure", async () => {
  const source = await jpegSource(32, 32);
  const mismatched = { ...original(source), eTag: '"different"' };
  const storage = new FakeStorage(mismatched);
  const publisher = new FakePublisher();
  const worker = new ImageTransformWorker(storage, publisher);

  await assert.rejects(worker.processMessage(requestBody(source)), ContractMismatchError);
  assert.equal(publisher.results.length, 0);
  assert.equal(storage.writes.length, 0);
});

test("retries unexpected encoder failures instead of publishing user failure", async () => {
  const source = await jpegSource(32, 32);
  const storage = new FakeStorage(original(source));
  const publisher = new FakePublisher();
  const worker = new ImageTransformWorker(storage, publisher, (input) =>
    processImage(input, async () => {
      throw new Error("transient encoder failure");
    }),
  );

  await assert.rejects(worker.processMessage(requestBody(source)), /transient encoder failure/);
  assert.equal(publisher.results.length, 0);
  assert.equal(storage.writes.length, 0);
});

test("rejects unknown purpose before reading the original", async () => {
  const source = await jpegSource(32, 32);
  const storage = new FakeStorage(original(source));
  const worker = new ImageTransformWorker(storage, new FakePublisher());

  await assert.rejects(
    worker.processMessage(requestBody(source, { purpose: "UNKNOWN_PURPOSE" })),
    ContractMismatchError,
  );
  assert.equal(storage.readRequests.length, 0);
  assert.equal(storage.writes.length, 0);
});

class FakeStorage implements ImageObjectStorage {
  readonly readRequests: ReadOriginalRequest[] = [];
  readonly writes: WriteRenditionRequest[] = [];

  constructor(private readonly source: OriginalObject) {}

  async readOriginal(request: ReadOriginalRequest): Promise<OriginalObject> {
    this.readRequests.push(request);
    return this.source;
  }

  async createRendition(request: WriteRenditionRequest): Promise<void> {
    this.writes.push(request);
  }
}

class FakePublisher implements TransformResultPublisher {
  readonly results: TransformResult[] = [];

  async publish(result: TransformResult): Promise<void> {
    this.results.push(result);
  }
}

async function jpegSource(width: number, height: number): Promise<Buffer> {
  return sharp({
    create: { width, height, channels: 3, background: "#b06244" },
  })
    .jpeg()
    .toBuffer();
}

function original(bytes: Buffer): OriginalObject {
  return {
    bytes,
    contentLength: bytes.length,
    contentType: "image/jpeg",
    eTag: ETAG,
    versionId: VERSION_ID,
  };
}

function requestBody(source: Buffer, overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    contractVersion: 1,
    jobId: JOB_ID,
    assetId: ASSET_ID,
    purpose: "PROFILE",
    specVersion: 1,
    specDigest: SPEC_DIGEST,
    originalKey: `media/originals/${ASSET_ID}/original`,
    sourceVersionId: VERSION_ID,
    sourceETag: ETAG,
    declaredContentType: "image/jpeg",
    declaredByteSize: source.length,
    ...overrides,
  });
}
