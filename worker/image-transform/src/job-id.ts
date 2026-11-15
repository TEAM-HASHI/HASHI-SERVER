import { createHash } from "node:crypto";

const MEDIA_PROCESSING_JOB_NAMESPACE = "5d167dc9-9bfd-5f4e-a7a0-46b10b4de90d";
const UUID_HEX_PATTERN = /^[0-9a-f]{32}$/;
const INT_BYTES = 4;

export function mediaProcessingJobId(
  assetId: string,
  sourceVersionId: string,
  specVersion: number,
): string {
  const namespaceBytes = uuidBytes(MEDIA_PROCESSING_JOB_NAMESPACE);
  const assetBytes = uuidBytes(assetId);
  const sourceVersionBytes = Buffer.from(sourceVersionId, "utf8");
  const canonicalName = Buffer.alloc(
    assetBytes.length + INT_BYTES + sourceVersionBytes.length + INT_BYTES,
  );

  let offset = 0;
  assetBytes.copy(canonicalName, offset);
  offset += assetBytes.length;
  canonicalName.writeInt32BE(sourceVersionBytes.length, offset);
  offset += INT_BYTES;
  sourceVersionBytes.copy(canonicalName, offset);
  offset += sourceVersionBytes.length;
  canonicalName.writeInt32BE(specVersion, offset);

  const hash = createHash("sha1")
    .update(namespaceBytes)
    .update(canonicalName)
    .digest();
  hash[6] = (hash[6]! & 0x0f) | 0x50;
  hash[8] = (hash[8]! & 0x3f) | 0x80;
  return formatUuid(hash.subarray(0, 16));
}

function uuidBytes(value: string): Buffer {
  const hex = value.replaceAll("-", "");
  if (!UUID_HEX_PATTERN.test(hex)) {
    throw new Error("Cannot derive a media processing job ID from an invalid UUID");
  }
  return Buffer.from(hex, "hex");
}

function formatUuid(bytes: Buffer): string {
  const hex = bytes.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join("-");
}
