import assert from "node:assert/strict";
import test from "node:test";

import {
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  type S3Client,
} from "@aws-sdk/client-s3";
import { SendMessageCommand, type SQSClient } from "@aws-sdk/client-sqs";

import { AwsImageObjectStorage, SqsTransformResultPublisher } from "../src/aws-adapters";
import { ContractMismatchError } from "../src/errors";
import type { TransformFailedResult } from "../src/queue-contract";

const JOB_ID = "f57dbf16-f7ca-46ec-8d80-8142be93d12a";
const OBJECT_KEY =
  "media/renditions/a3af06f1-4ef2-46f8-a489-2347fb840447/v1/profile-avatar/48.webp";
const CHECKSUM = "MDEyMzQ1Njc4OWFiY2RlZg==";
const CACHE_CONTROL = "public, max-age=31536000, immutable";

test("reads the exact original version with If-Match", async () => {
  const source = Buffer.from("source-image");
  const client = new FakeAwsClient([
    {
      Body: { transformToByteArray: async () => new Uint8Array(source) },
      ContentLength: source.length,
      ContentType: "image/jpeg",
      ETag: '"etag"',
      VersionId: "version-id",
    },
  ]);
  const storage = new AwsImageObjectStorage(
    "private-originals",
    "delivery",
    client as unknown as S3Client,
  );

  const result = await storage.readOriginal({
    objectKey: "media/originals/a3af06f1-4ef2-46f8-a489-2347fb840447/original",
    sourceETag: '"etag"',
    sourceVersionId: "version-id",
  });

  assert.deepEqual(result.bytes, source);
  const command = client.commands[0];
  assert.ok(command instanceof GetObjectCommand);
  assert.equal(command.input.Bucket, "private-originals");
  assert.equal(command.input.VersionId, "version-id");
  assert.equal(command.input.IfMatch, '"etag"');
});

test("conditionally creates an immutable WebP with checksum metadata", async () => {
  const client = new FakeAwsClient([{}]);
  const storage = new AwsImageObjectStorage(
    "private-originals",
    "delivery",
    client as unknown as S3Client,
  );
  const bytes = Buffer.from("webp-output");

  await storage.createRendition({
    objectKey: OBJECT_KEY,
    bytes,
    checksumSha256: CHECKSUM,
    jobId: JOB_ID,
  });

  const command = client.commands[0];
  assert.ok(command instanceof PutObjectCommand);
  assert.equal(command.input.Bucket, "delivery");
  assert.equal(command.input.Key, OBJECT_KEY);
  assert.equal(command.input.ContentType, "image/webp");
  assert.equal(command.input.CacheControl, CACHE_CONTROL);
  assert.equal(command.input.ChecksumSHA256, CHECKSUM);
  assert.equal(command.input.IfNoneMatch, "*");
  assert.deepEqual(command.input.Metadata, { "job-id": JOB_ID, sha256: CHECKSUM });
});

test("converges a duplicate create only when immutable metadata matches", async () => {
  const bytes = Buffer.from("webp-output");
  const client = new FakeAwsClient([
    preconditionFailed(),
    {
      ContentLength: bytes.length,
      ContentType: "image/webp",
      CacheControl: CACHE_CONTROL,
      ChecksumSHA256: CHECKSUM,
      Metadata: { "job-id": JOB_ID, sha256: CHECKSUM },
    },
  ]);
  const storage = new AwsImageObjectStorage(
    "private-originals",
    "delivery",
    client as unknown as S3Client,
  );

  await storage.createRendition({
    objectKey: OBJECT_KEY,
    bytes,
    checksumSha256: CHECKSUM,
    jobId: JOB_ID,
  });

  assert.ok(client.commands[0] instanceof PutObjectCommand);
  const head = client.commands[1];
  assert.ok(head instanceof HeadObjectCommand);
  assert.equal(head.input.ChecksumMode, "ENABLED");
});

test("rejects a duplicate key whose checksum differs", async () => {
  const bytes = Buffer.from("webp-output");
  const client = new FakeAwsClient([
    preconditionFailed(),
    {
      ContentLength: bytes.length,
      ContentType: "image/webp",
      CacheControl: CACHE_CONTROL,
      ChecksumSHA256: "different",
      Metadata: { "job-id": JOB_ID, sha256: "different" },
    },
  ]);
  const storage = new AwsImageObjectStorage(
    "private-originals",
    "delivery",
    client as unknown as S3Client,
  );

  await assert.rejects(
    storage.createRendition({
      objectKey: OBJECT_KEY,
      bytes,
      checksumSha256: CHECKSUM,
      jobId: JOB_ID,
    }),
    ContractMismatchError,
  );
});

test("publishes only the result contract to the configured queue", async () => {
  const client = new FakeAwsClient([{}]);
  const publisher = new SqsTransformResultPublisher(
    "https://sqs.ap-northeast-2.amazonaws.com/123/result",
    client as unknown as SQSClient,
  );
  const result: TransformFailedResult = {
    contractVersion: 1,
    jobId: JOB_ID,
    assetId: "a3af06f1-4ef2-46f8-a489-2347fb840447",
    specVersion: 1,
    specDigest: "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32",
    sourceVersionId: "version-id",
    sourceETag: '"etag"',
    status: "FAILED",
    failureCode: "INVALID_IMAGE_DATA",
  };

  await publisher.publish(result);

  const command = client.commands[0];
  assert.ok(command instanceof SendMessageCommand);
  assert.equal(
    command.input.QueueUrl,
    "https://sqs.ap-northeast-2.amazonaws.com/123/result",
  );
  assert.deepEqual(JSON.parse(command.input.MessageBody!), result);
});

class FakeAwsClient {
  readonly commands: unknown[] = [];

  constructor(private readonly responses: unknown[]) {}

  async send(command: unknown): Promise<unknown> {
    this.commands.push(command);
    const response = this.responses.shift();
    if (response instanceof Error) {
      throw response;
    }
    if (isThrowableResponse(response)) {
      throw response;
    }
    return response;
  }
}

function preconditionFailed() {
  return {
    name: "PreconditionFailed",
    $metadata: { httpStatusCode: 412 },
    __throw: true,
  };
}

function isThrowableResponse(value: unknown): value is { readonly __throw: true } {
  return (
    typeof value === "object" &&
    value !== null &&
    "__throw" in value &&
    (value as { readonly __throw?: unknown }).__throw === true
  );
}
