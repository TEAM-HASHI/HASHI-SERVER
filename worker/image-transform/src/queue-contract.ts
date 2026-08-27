import { ContractMismatchError } from "./errors";
import type { PermanentFailureCode } from "./errors";
import { IMAGE_LIMITS } from "./image-processor";
import { SUPPORTED_SOURCE_MIME_TYPES, type SourceMimeType } from "./mime";

export interface TransformRequest {
  readonly contractVersion: 1;
  readonly jobId: string;
  readonly assetId: string;
  readonly purpose: string;
  readonly specVersion: number;
  readonly specDigest: string;
  readonly originalKey: string;
  readonly sourceVersionId: string;
  readonly sourceETag: string;
  readonly declaredContentType: SourceMimeType;
  readonly declaredByteSize: number;
}

export interface VerifiedSourceResult {
  readonly mimeType: SourceMimeType;
  readonly byteSize: number;
  readonly width: number;
  readonly height: number;
  readonly checksumSha256: string;
}

export interface RenditionResult {
  readonly role: string;
  readonly format: "WEBP";
  readonly width: number;
  readonly height: number;
  readonly byteSize: number;
  readonly objectKey: string;
}

interface TransformResultIdentity {
  readonly contractVersion: 1;
  readonly jobId: string;
  readonly assetId: string;
  readonly specVersion: number;
  readonly specDigest: string;
  readonly sourceVersionId: string;
  readonly sourceETag: string;
}

export interface TransformSucceededResult extends TransformResultIdentity {
  readonly status: "SUCCEEDED";
  readonly verifiedSource: VerifiedSourceResult;
  readonly renditions: readonly RenditionResult[];
}

export interface TransformFailedResult extends TransformResultIdentity {
  readonly status: "FAILED";
  readonly failureCode: PermanentFailureCode;
}

export type TransformResult = TransformSucceededResult | TransformFailedResult;

const REQUEST_KEYS = new Set([
  "contractVersion",
  "jobId",
  "assetId",
  "purpose",
  "specVersion",
  "specDigest",
  "originalKey",
  "sourceVersionId",
  "sourceETag",
  "declaredContentType",
  "declaredByteSize",
]);
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SPEC_DIGEST_PATTERN = /^[0-9a-f]{64}$/;
const PURPOSE_PATTERN = /^[A-Z][A-Z0-9_]*$/;

export function parseTransformRequest(body: string): TransformRequest {
  let value: unknown;
  try {
    value = JSON.parse(body) as unknown;
  } catch (error) {
    throw new ContractMismatchError("Transform request is not valid JSON", { cause: error });
  }

  if (!isRecord(value) || !hasExactKeys(value, REQUEST_KEYS)) {
    throw new ContractMismatchError("Transform request fields do not match contract v1");
  }
  if (value.contractVersion !== 1) {
    throw new ContractMismatchError("Transform request contractVersion is unsupported");
  }
  if (!isCanonicalUuid(value.jobId) || !isCanonicalUuid(value.assetId)) {
    throw new ContractMismatchError("Transform request identifiers are invalid");
  }
  if (typeof value.purpose !== "string" || !PURPOSE_PATTERN.test(value.purpose)) {
    throw new ContractMismatchError("Transform request purpose is invalid");
  }
  if (!Number.isSafeInteger(value.specVersion) || (value.specVersion as number) < 1) {
    throw new ContractMismatchError("Transform request specVersion is invalid");
  }
  if (typeof value.specDigest !== "string" || !SPEC_DIGEST_PATTERN.test(value.specDigest)) {
    throw new ContractMismatchError("Transform request specDigest is invalid");
  }
  if (
    typeof value.originalKey !== "string" ||
    value.originalKey !== `media/originals/${value.assetId}/original`
  ) {
    throw new ContractMismatchError("Transform request originalKey is outside the asset prefix");
  }
  if (!isBoundedText(value.sourceVersionId, 1, 1_024) || !isBoundedText(value.sourceETag, 1, 255)) {
    throw new ContractMismatchError("Transform request source identity is invalid");
  }
  if (
    typeof value.declaredContentType !== "string" ||
    !SUPPORTED_SOURCE_MIME_TYPES.includes(value.declaredContentType as SourceMimeType)
  ) {
    throw new ContractMismatchError("Transform request declaredContentType is invalid");
  }
  if (
    !Number.isSafeInteger(value.declaredByteSize) ||
    (value.declaredByteSize as number) < 1 ||
    (value.declaredByteSize as number) > IMAGE_LIMITS.maxBytes
  ) {
    throw new ContractMismatchError("Transform request declaredByteSize is invalid");
  }

  return Object.freeze(value as unknown as TransformRequest);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, expected: ReadonlySet<string>): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.size && keys.every((key) => expected.has(key));
}

function isCanonicalUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

function isBoundedText(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === "string" && value.length >= minimum && value.length <= maximum;
}
