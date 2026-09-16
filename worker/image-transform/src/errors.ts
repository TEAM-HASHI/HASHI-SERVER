export type PermanentFailureCode =
  | "ANIMATED_IMAGE_NOT_SUPPORTED"
  | "IMAGE_DIMENSION_LIMIT_EXCEEDED"
  | "IMAGE_PIXEL_LIMIT_EXCEEDED"
  | "INVALID_IMAGE_DATA"
  | "SOURCE_FILE_TOO_LARGE"
  | "SOURCE_MIME_MISMATCH"
  | "SOURCE_SIZE_MISMATCH"
  | "UNSUPPORTED_IMAGE_TYPE";

export class PermanentImageError extends Error {
  constructor(
    readonly failureCode: PermanentFailureCode,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "PermanentImageError";
  }
}

export class ContractMismatchError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ContractMismatchError";
  }
}
