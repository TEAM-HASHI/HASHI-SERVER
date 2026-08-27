import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const packageRoot = resolve(process.argv[2] ?? "artifacts/package");
const packageRequire = createRequire(resolve(packageRoot, "package.json"));

assert.equal(process.platform, "linux", "Lambda package smoke test must run on Linux");
assert.equal(process.arch, "x64", "Lambda package smoke test must run on x64");
assert.equal(existsSync(resolve(packageRoot, "media-specs", "v1.json")), true);
assert.equal(existsSync(resolve(packageRoot, "node_modules", "typescript")), false);

const sharp = packageRequire("sharp");
const lambdaHandler = packageRequire("./dist/handler.js");
const manifestModule = packageRequire("./dist/manifest.js");

assert.equal(sharp.versions.sharp, "0.35.4");
assert.equal(typeof lambdaHandler.handler, "function");
process.env.MEDIA_SPECS_DIRECTORY = resolve(packageRoot, "media-specs");
const spec = manifestModule.loadMediaSpec(1);
assert.equal(
  spec.digest,
  "91ac56d691c5af9e43061b0a2cc43763a4d1825244135d120057c3305a1bbe32",
);

console.log(
  JSON.stringify({
    architecture: process.arch,
    handler: "dist/handler.handler",
    node: process.version,
    platform: process.platform,
    sharp: sharp.versions.sharp,
    specDigest: spec.digest,
  }),
);
