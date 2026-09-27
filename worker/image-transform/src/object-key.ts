import { ContractMismatchError } from "./errors";

const ASSET_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ROLE_PATTERN = /^[A-Z][A-Z0-9_]*$/;

export function renditionObjectKey(
  assetId: string,
  specVersion: number,
  role: string,
  width: number,
): string {
  if (!ASSET_ID_PATTERN.test(assetId)) {
    throw new ContractMismatchError("Cannot build rendition key for an invalid asset ID");
  }
  if (!Number.isSafeInteger(specVersion) || specVersion < 1) {
    throw new ContractMismatchError("Cannot build rendition key for an invalid spec version");
  }
  if (!ROLE_PATTERN.test(role)) {
    throw new ContractMismatchError("Cannot build rendition key for an invalid role");
  }
  if (!Number.isSafeInteger(width) || width < 1) {
    throw new ContractMismatchError("Cannot build rendition key for an invalid width");
  }

  const roleSegment = role.toLowerCase().replaceAll("_", "-");
  return `media/renditions/${assetId}/v${specVersion}/${roleSegment}/${width}.webp`;
}
