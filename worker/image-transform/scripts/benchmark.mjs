import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { availableParallelism, cpus } from "node:os";
import { dirname, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

// Run once per fresh Node process, after npm test has produced dist/src.
// Input creation, file reading and manifest loading are outside the elapsed timer.
const require = createRequire(import.meta.url);
const workerDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const expectedRenditionCount = 9;

async function main() {
  if (process.argv.length !== 3) {
    throw new Error("EXPECTED_ONE_LOCAL_INPUT_FILE");
  }

  const sharp = require("sharp");
  const { processImage } = require("../dist/src/image-processor.js");
  const { loadMediaSpec } = require("../dist/src/manifest.js");
  const { detectSourceMimeType } = require("../dist/src/mime.js");
  const bytes = readFileSync(process.argv[2]);
  const spec = loadMediaSpec(1, resolve(workerDirectory, "..", "..", "media-specs"));
  const declaredContentType = detectSourceMimeType(bytes);
  const rssBeforeTransformBytes = process.memoryUsage().rss;
  const startedAt = performance.now();
  const result = await processImage({
    bytes,
    declaredByteSize: bytes.length,
    declaredContentType,
    purpose: "RESTAURANT",
    spec,
  });
  const validationAndRenditionElapsedMs = performance.now() - startedAt;
  const processPeakRssKiB = process.resourceUsage().maxRSS;
  const completeCandidateSet = result.renditions.length === expectedRenditionCount;

  console.log(JSON.stringify({
    status: completeCandidateSet ? "MEASURED" : "INSUFFICIENT_STANDARD_CANDIDATES",
    purpose: "RESTAURANT",
    specVersion: spec.manifest.specVersion,
    specDigest: spec.digest,
    iterations: 1,
    warmupIterations: 0,
    validationAndRenditionElapsedMs,
    processPeakRssKiB,
    rssBeforeTransformBytes,
    memoryScope: "Whole-process peak through transformation, including runtime, module loading and input buffer; not transformation-only memory",
    elapsedScope: "processImage source validation and rendition generation; excludes input creation, file reading and manifest loading",
    inputBytes: bytes.length,
    outputBytes: result.renditions.reduce((total, rendition) => total + rendition.byteSize, 0),
    sourceWidth: result.verifiedSource.width,
    sourceHeight: result.verifiedSource.height,
    expectedRenditionCount,
    renditionCount: result.renditions.length,
    renditions: result.renditions.map(({ role, width, height, byteSize }) => ({
      role, width, height, byteSize,
    })),
    environment: {
      node: process.version,
      platform: process.platform,
      architecture: process.arch,
      cpuModel: cpus()[0]?.model ?? "unknown",
      availableParallelism: availableParallelism(),
      sharpVersions: sharp.versions,
      sharpConcurrency: sharp.concurrency(),
      sharpCache: sharp.cache(),
    },
  }, null, 2));
  if (!completeCandidateSet) {
    process.exitCode = 1;
  }
}

main().catch(() => {
  // Native errors may contain the input path; never print raw error details.
  console.error(JSON.stringify({
    status: "BENCHMARK_FAILED",
    reason: "Use one readable local image and run npm test first; the image must pass worker validation",
  }));
  process.exitCode = 1;
});
