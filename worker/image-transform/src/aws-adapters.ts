import {
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
  type S3ClientConfig,
  S3ServiceException,
} from "@aws-sdk/client-s3";
import { SendMessageCommand, SQSClient, type SQSClientConfig } from "@aws-sdk/client-sqs";

import { ContractMismatchError } from "./errors";
import type {
  ImageObjectStorage,
  OriginalObject,
  ReadOriginalRequest,
  TransformResultPublisher,
  WriteRenditionRequest,
} from "./ports";
import type { TransformResult } from "./queue-contract";

const WEBP_CONTENT_TYPE = "image/webp";
const IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";
const MAX_ORIGINAL_BYTES = 10 * 1024 * 1024;

export class AwsImageObjectStorage implements ImageObjectStorage {
  private readonly client: S3Client;

  constructor(
    private readonly originalBucket: string,
    private readonly deliveryBucket: string,
    client?: S3Client,
    clientConfig: S3ClientConfig = {},
  ) {
    this.client = client ?? new S3Client(clientConfig);
  }

  async readOriginal(request: ReadOriginalRequest): Promise<OriginalObject> {
    const response = await this.client.send(
      new GetObjectCommand({
        Bucket: this.originalBucket,
        Key: request.objectKey,
        VersionId: request.sourceVersionId,
        IfMatch: request.sourceETag,
      }),
    );
    if (response.Body === undefined) {
      throw new ContractMismatchError("S3 returned an original object without a body");
    }
    if (response.ContentLength === undefined || response.ContentLength > MAX_ORIGINAL_BYTES) {
      throw new ContractMismatchError("S3 original content length exceeds the worker limit");
    }
    if (response.ContentLength !== request.expectedContentLength) {
      throw new ContractMismatchError("S3 original content length differs from transform request");
    }

    const bytes = Buffer.from(await response.Body.transformToByteArray());
    return Object.freeze({
      bytes,
      contentLength: response.ContentLength ?? -1,
      contentType: response.ContentType,
      eTag: response.ETag,
      versionId: response.VersionId,
    });
  }

  async createRendition(request: WriteRenditionRequest): Promise<void> {
    try {
      await this.client.send(
        new PutObjectCommand({
          Bucket: this.deliveryBucket,
          Key: request.objectKey,
          Body: request.bytes,
          ContentLength: request.bytes.length,
          ContentType: WEBP_CONTENT_TYPE,
          CacheControl: IMMUTABLE_CACHE_CONTROL,
          ChecksumSHA256: request.checksumSha256,
          IfNoneMatch: "*",
          Metadata: {
            "job-id": request.jobId,
            sha256: request.checksumSha256,
          },
        }),
      );
    } catch (error) {
      if (!isPreconditionFailed(error)) {
        throw error;
      }
      await this.assertExistingRenditionMatches(request);
    }
  }

  private async assertExistingRenditionMatches(request: WriteRenditionRequest): Promise<void> {
    const existing = await this.client.send(
      new HeadObjectCommand({
        Bucket: this.deliveryBucket,
        Key: request.objectKey,
        ChecksumMode: "ENABLED",
      }),
    );
    const metadataChecksum = existing.Metadata?.sha256;
    const metadataJobId = existing.Metadata?.["job-id"];
    if (
      existing.ContentType !== WEBP_CONTENT_TYPE ||
      existing.CacheControl !== IMMUTABLE_CACHE_CONTROL ||
      existing.ContentLength !== request.bytes.length ||
      existing.ChecksumSHA256 !== request.checksumSha256 ||
      metadataChecksum !== request.checksumSha256 ||
      metadataJobId !== request.jobId
    ) {
      throw new ContractMismatchError("Existing rendition violates immutable object contract");
    }
  }
}

export class SqsTransformResultPublisher implements TransformResultPublisher {
  private readonly client: SQSClient;

  constructor(
    private readonly queueUrl: string,
    client?: SQSClient,
    clientConfig: SQSClientConfig = {},
  ) {
    this.client = client ?? new SQSClient(clientConfig);
  }

  async publish(result: TransformResult): Promise<void> {
    await this.client.send(
      new SendMessageCommand({
        QueueUrl: this.queueUrl,
        MessageBody: JSON.stringify(result),
      }),
    );
  }
}

function isPreconditionFailed(error: unknown): boolean {
  if (error instanceof S3ServiceException) {
    return error.$metadata.httpStatusCode === 412 || error.name === "PreconditionFailed";
  }
  if (typeof error !== "object" || error === null) {
    return false;
  }
  const candidate = error as {
    readonly name?: unknown;
    readonly $metadata?: { readonly httpStatusCode?: unknown };
  };
  return candidate.name === "PreconditionFailed" || candidate.$metadata?.httpStatusCode === 412;
}
