import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

import Ajv2020 from "ajv/dist/2020";

import { ContractMismatchError } from "./errors";

export interface RenditionDimensions {
  readonly width: number;
  readonly height: number;
}

export interface RenditionRoleSpec {
  readonly aspectRatio: RenditionDimensions;
  readonly fit: "cover";
  readonly position: "centre";
  readonly quality: number;
  readonly defaultWidth: number;
  readonly candidates: readonly RenditionDimensions[];
  readonly noUpscaleFallback: {
    readonly selection: "largest-croppable-width";
    readonly minimumWidth: 1;
  };
}

export interface MediaSpecManifest {
  readonly $schema?: string;
  readonly manifestSchemaVersion: 1;
  readonly specVersion: number;
  readonly processorRevision: "sharp-webp-v1";
  readonly output: {
    readonly format: "webp";
    readonly mimeType: "image/webp";
    readonly withoutEnlargement: true;
    readonly metadata: "strip";
    readonly colorSpace: "srgb";
    readonly dimensionRounding: "half-up";
  };
  readonly purposes: Readonly<Record<string, readonly string[]>>;
  readonly roles: Readonly<Record<string, RenditionRoleSpec>>;
}

export interface LoadedMediaSpec {
  readonly digest: string;
  readonly manifest: MediaSpecManifest;
  readonly rawBytes: Buffer;
}

const cache = new Map<string, LoadedMediaSpec>();

export function loadMediaSpec(specVersion: number, specsDirectory?: string): LoadedMediaSpec {
  if (!Number.isSafeInteger(specVersion) || specVersion < 1) {
    throw new ContractMismatchError("specVersion must be a positive integer");
  }

  const directory = specsDirectory ?? findSpecsDirectory(specVersion);
  const cacheKey = `${resolve(directory)}:${specVersion}`;
  const cached = cache.get(cacheKey);
  if (cached !== undefined) {
    return cached;
  }

  const manifestPath = resolve(directory, `v${specVersion}.json`);
  const schemaPath = resolve(directory, "schema.json");

  try {
    const rawBytes = readFileSync(manifestPath);
    const schema = JSON.parse(readFileSync(schemaPath, "utf8")) as object;
    const parsed = JSON.parse(rawBytes.toString("utf8")) as unknown;
    const ajv = new Ajv2020({ allErrors: true, strict: true });
    const validate = ajv.compile(schema);

    if (!validate(parsed)) {
      throw new ContractMismatchError(
        `Media spec v${specVersion} does not satisfy the canonical schema`,
      );
    }

    const manifest = parsed as MediaSpecManifest;
    if (manifest.specVersion !== specVersion) {
      throw new ContractMismatchError("Manifest version does not match its file name");
    }
    if (manifest.processorRevision !== "sharp-webp-v1") {
      throw new ContractMismatchError("Worker does not support the manifest processor revision");
    }
    assertUniqueCandidateWidths(manifest);

    const loaded = Object.freeze({
      digest: createHash("sha256").update(rawBytes).digest("hex"),
      manifest,
      rawBytes,
    });
    cache.set(cacheKey, loaded);
    return loaded;
  } catch (error) {
    if (error instanceof ContractMismatchError) {
      throw error;
    }
    throw new ContractMismatchError(`Unable to load media spec v${specVersion}`, {
      cause: error,
    });
  }
}

function assertUniqueCandidateWidths(manifest: MediaSpecManifest): void {
  for (const role of Object.values(manifest.roles)) {
    const widths = new Set<number>();
    for (const candidate of role.candidates) {
      if (widths.has(candidate.width)) {
        throw new ContractMismatchError("Media role candidate widths must be unique");
      }
      widths.add(candidate.width);
    }
  }
}

function findSpecsDirectory(specVersion: number): string {
  const configured = process.env.MEDIA_SPECS_DIRECTORY;
  const candidates = [
    configured === undefined ? undefined : resolve(configured),
    resolve(process.cwd(), "media-specs"),
    resolve(process.cwd(), "..", "..", "media-specs"),
  ].filter((candidate): candidate is string => candidate !== undefined);

  const directory = candidates.find(
    (candidate) =>
      existsSync(resolve(candidate, "schema.json")) &&
      existsSync(resolve(candidate, `v${specVersion}.json`)),
  );

  if (directory === undefined) {
    throw new ContractMismatchError(`Packaged media spec v${specVersion} is missing`);
  }
  return directory;
}
