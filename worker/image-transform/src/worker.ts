import { ContractMismatchError, PermanentImageError } from "./errors";
import {
  processImage,
  requiredRoles,
  type ProcessedImage,
  type ProcessImageInput,
} from "./image-processor";
import { loadMediaSpec } from "./manifest";
import { renditionObjectKey } from "./object-key";
import type { ImageObjectStorage, TransformResultPublisher } from "./ports";
import {
  parseTransformRequest,
  type RenditionResult,
  type TransformFailedResult,
  type TransformRequest,
  type TransformSucceededResult,
} from "./queue-contract";

export class ImageTransformWorker {
  constructor(
    private readonly storage: ImageObjectStorage,
    private readonly resultPublisher: TransformResultPublisher,
    private readonly imageProcessor: ImageProcessor = processImage,
  ) {}

  async processMessage(body: string): Promise<void> {
    const request = parseTransformRequest(body);
    const spec = loadMediaSpec(request.specVersion);
    if (spec.digest !== request.specDigest) {
      throw new ContractMismatchError("Request spec digest differs from packaged manifest");
    }
    requiredRoles(spec.manifest, request.purpose);
    const maxSourceBytes = request.purpose === "MAGAZINE_CARD_NEWS" && request.specVersion >= 2
      ? 10 * 1024 * 1024
      : 5 * 1024 * 1024;
    if (request.declaredByteSize > maxSourceBytes) {
      throw new ContractMismatchError("Transform request source size is outside the purpose limit");
    }

    const original = await this.storage.readOriginal({
      objectKey: request.originalKey,
      sourceETag: request.sourceETag,
      sourceVersionId: request.sourceVersionId,
      expectedContentLength: request.declaredByteSize,
    });
    this.assertOriginalIdentity(request, original);

    try {
      const processed = await this.imageProcessor({
        bytes: original.bytes,
        declaredByteSize: request.declaredByteSize,
        declaredContentType: request.declaredContentType,
        purpose: request.purpose,
        spec,
      });
      const renditionResults: RenditionResult[] = [];
      for (const rendition of processed.renditions) {
        const objectKey = renditionObjectKey(
          request.assetId,
          request.specVersion,
          rendition.role,
          rendition.width,
        );
        await this.storage.createRendition({
          objectKey,
          bytes: rendition.bytes,
          checksumSha256: rendition.checksumSha256,
          jobId: request.jobId,
        });
        renditionResults.push(
          Object.freeze({
            role: rendition.role,
            format: rendition.format,
            width: rendition.width,
            height: rendition.height,
            byteSize: rendition.byteSize,
            objectKey,
          }),
        );
      }

      const result: TransformSucceededResult = Object.freeze({
        ...resultIdentity(request),
        status: "SUCCEEDED",
        verifiedSource: processed.verifiedSource,
        renditions: Object.freeze(renditionResults),
      });
      await this.resultPublisher.publish(result);
    } catch (error) {
      if (!(error instanceof PermanentImageError)) {
        throw error;
      }
      const result: TransformFailedResult = Object.freeze({
        ...resultIdentity(request),
        status: "FAILED",
        failureCode: error.failureCode,
      });
      await this.resultPublisher.publish(result);
    }
  }

  private assertOriginalIdentity(
    request: TransformRequest,
    original: {
      readonly contentLength: number;
      readonly contentType: string | undefined;
      readonly eTag: string | undefined;
      readonly versionId: string | undefined;
    },
  ): void {
    if (
      original.versionId !== request.sourceVersionId ||
      original.eTag !== request.sourceETag ||
      original.contentType !== request.declaredContentType ||
      original.contentLength !== request.declaredByteSize
    ) {
      throw new ContractMismatchError("Original object identity differs from transform request");
    }
  }
}

export type ImageProcessor = (input: ProcessImageInput) => Promise<ProcessedImage>;

function resultIdentity(request: TransformRequest) {
  return {
    contractVersion: request.contractVersion,
    jobId: request.jobId,
    assetId: request.assetId,
    specVersion: request.specVersion,
    specDigest: request.specDigest,
    sourceVersionId: request.sourceVersionId,
    sourceETag: request.sourceETag,
  } as const;
}
