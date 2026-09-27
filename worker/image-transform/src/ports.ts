import type { TransformResult } from "./queue-contract";

export interface OriginalObject {
  readonly bytes: Buffer;
  readonly contentLength: number;
  readonly contentType: string | undefined;
  readonly eTag: string | undefined;
  readonly versionId: string | undefined;
}

export interface ReadOriginalRequest {
  readonly objectKey: string;
  readonly sourceETag: string;
  readonly sourceVersionId: string;
  readonly expectedContentLength: number;
}

export interface WriteRenditionRequest {
  readonly objectKey: string;
  readonly bytes: Buffer;
  readonly checksumSha256: string;
  readonly jobId: string;
}

export interface ImageObjectStorage {
  readOriginal(request: ReadOriginalRequest): Promise<OriginalObject>;

  createRendition(request: WriteRenditionRequest): Promise<void>;
}

export interface TransformResultPublisher {
  publish(result: TransformResult): Promise<void>;
}
