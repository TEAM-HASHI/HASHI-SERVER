import { PermanentImageError } from "./errors";

export const SUPPORTED_SOURCE_MIME_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
] as const;

export type SourceMimeType = (typeof SUPPORTED_SOURCE_MIME_TYPES)[number];

export function detectSourceMimeType(bytes: Buffer): SourceMimeType {
  if (
    bytes.length >= 3 &&
    bytes[0] === 0xff &&
    bytes[1] === 0xd8 &&
    bytes[2] === 0xff
  ) {
    return "image/jpeg";
  }

  if (
    bytes.length >= 8 &&
    bytes.subarray(0, 8).equals(
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    )
  ) {
    return "image/png";
  }

  if (
    bytes.length >= 12 &&
    bytes.toString("ascii", 0, 4) === "RIFF" &&
    bytes.toString("ascii", 8, 12) === "WEBP"
  ) {
    return "image/webp";
  }

  throw new PermanentImageError(
    "UNSUPPORTED_IMAGE_TYPE",
    "Source magic bytes are not an allowed image type",
  );
}

export function mimeTypeForSharpFormat(format: string | undefined): SourceMimeType {
  switch (format) {
    case "jpeg":
      return "image/jpeg";
    case "png":
      return "image/png";
    case "webp":
      return "image/webp";
    default:
      throw new PermanentImageError(
        "UNSUPPORTED_IMAGE_TYPE",
        "Decoded image format is not supported",
      );
  }
}

export function isAnimatedPng(bytes: Buffer): boolean {
  if (
    bytes.length < 8 ||
    !bytes.subarray(0, 8).equals(
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    )
  ) {
    return false;
  }

  let offset = 8;
  while (offset + 12 <= bytes.length) {
    const dataLength = bytes.readUInt32BE(offset);
    const chunkEnd = offset + 12 + dataLength;
    if (!Number.isSafeInteger(chunkEnd) || chunkEnd > bytes.length) {
      return false;
    }

    const chunkType = bytes.toString("ascii", offset + 4, offset + 8);
    if (chunkType === "acTL") {
      return true;
    }
    if (chunkType === "IEND") {
      return false;
    }
    offset = chunkEnd;
  }
  return false;
}
