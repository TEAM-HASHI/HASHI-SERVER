import { createHash } from "node:crypto";

import sharp, { type Metadata } from "sharp";

import { ContractMismatchError, PermanentImageError } from "./errors";
import {
  type LoadedMediaSpec,
  type MediaSpecManifest,
  type RenditionDimensions,
  type RenditionRoleSpec,
} from "./manifest";
import {
  detectSourceMimeType,
  isAnimatedPng,
  mimeTypeForSharpFormat,
  type SourceMimeType,
} from "./mime";

export const IMAGE_LIMITS = Object.freeze({
  maxBytes: 5 * 1024 * 1024,
  maxDimension: 10_000,
  maxFramePixels: 40_000_000,
  maxTotalDecodePixels: 40_000_000,
});

export const WEBP_ENCODER_V1 = Object.freeze({
  alphaQuality: 100,
  effort: 4,
  lossless: false,
  nearLossless: false,
  smartSubsample: true,
});

export interface VerifiedSource {
  readonly mimeType: SourceMimeType;
  readonly byteSize: number;
  readonly width: number;
  readonly height: number;
  readonly checksumSha256: string;
}

export interface GeneratedRendition {
  readonly role: string;
  readonly format: "WEBP";
  readonly width: number;
  readonly height: number;
  readonly byteSize: number;
  readonly checksumSha256: string;
  readonly bytes: Buffer;
}

export interface ProcessedImage {
  readonly verifiedSource: VerifiedSource;
  readonly renditions: readonly GeneratedRendition[];
}

export interface ProcessImageInput {
  readonly bytes: Buffer;
  readonly declaredByteSize: number;
  readonly declaredContentType: string;
  readonly purpose: string;
  readonly spec: LoadedMediaSpec;
}

interface InspectedSource {
  readonly verified: VerifiedSource;
  readonly orientedWidth: number;
  readonly orientedHeight: number;
}

export async function processImage(input: ProcessImageInput): Promise<ProcessedImage> {
  const source = await inspectSource(
    input.bytes,
    input.declaredContentType,
    input.declaredByteSize,
  );
  const roles = input.spec.manifest.purposes[input.purpose];

  if (roles === undefined || roles.length === 0) {
    throw new ContractMismatchError("Purpose is not defined by the canonical manifest");
  }

  const renditions: GeneratedRendition[] = [];
  for (const role of roles) {
    const roleSpec = input.spec.manifest.roles[role];
    if (roleSpec === undefined) {
      throw new ContractMismatchError("Purpose references an unknown rendition role");
    }

    const dimensions = selectRenditionDimensions(
      source.orientedWidth,
      source.orientedHeight,
      roleSpec,
    );
    for (const target of dimensions) {
      renditions.push(await renderRendition(input.bytes, role, roleSpec, target));
    }
  }

  return Object.freeze({
    verifiedSource: source.verified,
    renditions: Object.freeze(renditions),
  });
}

export async function inspectSource(
  bytes: Buffer,
  declaredContentType: string,
  declaredByteSize: number,
): Promise<InspectedSource> {
  if (bytes.length > IMAGE_LIMITS.maxBytes) {
    throw new PermanentImageError("SOURCE_FILE_TOO_LARGE", "Source exceeds byte limit");
  }
  if (bytes.length !== declaredByteSize) {
    throw new PermanentImageError("SOURCE_SIZE_MISMATCH", "Source byte size differs from request");
  }

  const magicMimeType = detectSourceMimeType(bytes);
  if (magicMimeType !== declaredContentType.toLowerCase()) {
    throw new PermanentImageError(
      "SOURCE_MIME_MISMATCH",
      "Source magic bytes differ from declared MIME",
    );
  }
  if (magicMimeType === "image/png" && isAnimatedPng(bytes)) {
    throw new PermanentImageError(
      "ANIMATED_IMAGE_NOT_SUPPORTED",
      "Animated PNG sources are not supported",
    );
  }

  let metadata: Metadata;
  try {
    metadata = await sharp(bytes, sharpInputOptions(true)).metadata();
  } catch (error) {
    throw new PermanentImageError("INVALID_IMAGE_DATA", "Source metadata cannot be decoded", {
      cause: error,
    });
  }

  const decodedMimeType = mimeTypeForSharpFormat(metadata.format);
  if (decodedMimeType !== magicMimeType) {
    throw new PermanentImageError(
      "SOURCE_MIME_MISMATCH",
      "Decoder format differs from source magic bytes",
    );
  }

  const dimensions = assertImageMetadataWithinLimits(metadata);

  try {
    await sharp(bytes, sharpInputOptions(false)).rotate().stats();
  } catch (error) {
    throw new PermanentImageError("INVALID_IMAGE_DATA", "Source pixel data cannot be decoded", {
      cause: error,
    });
  }

  const orientationSwapsDimensions =
    metadata.orientation !== undefined && metadata.orientation >= 5 && metadata.orientation <= 8;
  const orientedWidth = orientationSwapsDimensions ? dimensions.frameHeight : dimensions.width;
  const orientedHeight = orientationSwapsDimensions ? dimensions.width : dimensions.frameHeight;

  return Object.freeze({
    orientedWidth,
    orientedHeight,
    verified: Object.freeze({
      mimeType: decodedMimeType,
      byteSize: bytes.length,
      width: orientedWidth,
      height: orientedHeight,
      checksumSha256: sha256Base64(bytes),
    }),
  });
}

export function assertImageMetadataWithinLimits(metadata: Metadata): {
  readonly width: number;
  readonly frameHeight: number;
  readonly pages: number;
} {
  const width = metadata.width;
  const totalHeight = metadata.height;
  const pages = metadata.pages ?? 1;
  const frameHeight = metadata.pageHeight ?? totalHeight;

  if (
    !Number.isSafeInteger(width) ||
    !Number.isSafeInteger(totalHeight) ||
    !Number.isSafeInteger(frameHeight) ||
    !Number.isSafeInteger(pages) ||
    width === undefined ||
    totalHeight === undefined ||
    frameHeight === undefined ||
    width < 1 ||
    totalHeight < 1 ||
    frameHeight < 1 ||
    pages < 1
  ) {
    throw new PermanentImageError("INVALID_IMAGE_DATA", "Source dimensions are invalid");
  }

  if (width > IMAGE_LIMITS.maxDimension || frameHeight > IMAGE_LIMITS.maxDimension) {
    throw new PermanentImageError(
      "IMAGE_DIMENSION_LIMIT_EXCEEDED",
      "Source dimension exceeds limit",
    );
  }

  const framePixels = width * frameHeight;
  const totalDecodePixels = framePixels * pages;
  if (framePixels > IMAGE_LIMITS.maxFramePixels) {
    throw new PermanentImageError(
      "IMAGE_PIXEL_LIMIT_EXCEEDED",
      "Source frame pixel count exceeds limit",
    );
  }
  if (totalDecodePixels > IMAGE_LIMITS.maxTotalDecodePixels) {
    throw new PermanentImageError(
      "IMAGE_PIXEL_LIMIT_EXCEEDED",
      "Source total decode pixel count exceeds limit",
    );
  }
  if (pages !== 1) {
    throw new PermanentImageError(
      "ANIMATED_IMAGE_NOT_SUPPORTED",
      "Animated and multi-page sources are not supported",
    );
  }

  return Object.freeze({ width, frameHeight, pages });
}

export function selectRenditionDimensions(
  sourceWidth: number,
  sourceHeight: number,
  role: RenditionRoleSpec,
): readonly RenditionDimensions[] {
  const standard = role.candidates
    .filter((candidate) => candidate.width <= sourceWidth && candidate.height <= sourceHeight)
    .toSorted((left, right) => left.width - right.width);

  if (standard.length > 0) {
    return Object.freeze(standard);
  }

  for (let width = sourceWidth; width >= role.noUpscaleFallback.minimumWidth; width -= 1) {
    const height = roundHalfUp((width * role.aspectRatio.height) / role.aspectRatio.width);
    if (height >= 1 && height <= sourceHeight) {
      return Object.freeze([Object.freeze({ width, height })]);
    }
  }

  throw new PermanentImageError(
    "IMAGE_DIMENSION_LIMIT_EXCEEDED",
    "Source is too small to create a no-upscale rendition",
  );
}

function roundHalfUp(value: number): number {
  return Math.floor(value + 0.5);
}

async function renderRendition(
  source: Buffer,
  role: string,
  roleSpec: RenditionRoleSpec,
  target: RenditionDimensions,
): Promise<GeneratedRendition> {
  let output: { data: Buffer; info: sharp.OutputInfo };
  try {
    output = await sharp(source, sharpInputOptions(false))
      .rotate()
      .resize({
        width: target.width,
        height: target.height,
        fit: roleSpec.fit,
        position: roleSpec.position,
        kernel: sharp.kernel.lanczos3,
        withoutEnlargement: true,
      })
      .toColourspace("srgb")
      .webp({
        ...WEBP_ENCODER_V1,
        quality: roleSpec.quality,
      })
      .toBuffer({ resolveWithObject: true });
  } catch (error) {
    throw new PermanentImageError("INVALID_IMAGE_DATA", "Source rendition cannot be encoded", {
      cause: error,
    });
  }

  if (output.info.width !== target.width || output.info.height !== target.height) {
    throw new ContractMismatchError("Sharp output dimensions differ from the canonical manifest");
  }

  return Object.freeze({
    role,
    format: "WEBP",
    width: output.info.width,
    height: output.info.height,
    byteSize: output.data.length,
    checksumSha256: sha256Base64(output.data),
    bytes: output.data,
  });
}

function sharpInputOptions(animated: boolean): sharp.SharpOptions {
  return {
    animated,
    failOn: "error",
    limitInputPixels: IMAGE_LIMITS.maxFramePixels,
    pages: animated ? -1 : 1,
    sequentialRead: true,
  };
}

function sha256Base64(bytes: Buffer): string {
  return createHash("sha256").update(bytes).digest("base64");
}

export function requiredRoles(manifest: MediaSpecManifest, purpose: string): readonly string[] {
  const roles = manifest.purposes[purpose];
  if (roles === undefined || roles.length === 0) {
    throw new ContractMismatchError("Purpose is not defined by the canonical manifest");
  }
  return roles;
}
