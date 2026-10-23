import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const packageRoot = resolve(process.argv[2] ?? "artifacts/package");
const packageRequire = createRequire(resolve(packageRoot, "package.json"));

assert.equal(process.platform, "linux", "Lambda package smoke test must run on Linux");
assert.equal(process.arch, "x64", "Lambda package smoke test must run on x64");
assert.equal(existsSync(resolve(packageRoot, "media-specs", "v1.json")), true);
assert.equal(existsSync(resolve(packageRoot, "media-specs", "v2.json")), true);
assert.equal(existsSync(resolve(packageRoot, "media-specs", "v3.json")), true);
assert.equal(existsSync(resolve(packageRoot, "media-specs", "v4.json")), true);
assert.equal(existsSync(resolve(packageRoot, "node_modules", "typescript")), false);

const sharp = packageRequire("sharp");
const lambdaHandler = packageRequire("./dist/handler.js");
const manifestModule = packageRequire("./dist/manifest.js");
const { processImage } = packageRequire("./dist/image-processor.js");

assert.equal(sharp.versions.sharp, "0.35.5");
assert.equal(sharp.versions.rsvg, "2.63.2");
assert.equal(packageRequire("fast-uri/package.json").version, "3.1.9");
assert.equal(typeof lambdaHandler.handler, "function");
process.env.MEDIA_SPECS_DIRECTORY = resolve(packageRoot, "media-specs");
const spec = manifestModule.loadMediaSpec(1);
assert.equal(
  spec.digest,
  "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f",
);
const cardNewsSpec = manifestModule.loadMediaSpec(2);
assert.equal(
  cardNewsSpec.digest,
  "b8e67084bcdf81ac7fc94951c905726a97ade3783320c67e03c505f5643ddf75",
);

const noticeSpec = manifestModule.loadMediaSpec(3);
assert.equal(noticeSpec.digest, "ab130db4caea347d5945531e22c9f2b783a95ad1a9c9fa7b5741a6adcd16b97a");

// Exercise the native decoder and encoder from the extracted ZIP, not the build tree.
const source = await sharp({
  create: { width: 96, height: 96, channels: 3, background: "#557799" },
}).png().toBuffer();
const transformed = await processImage({
  bytes: source,
  declaredByteSize: source.length,
  declaredContentType: "image/png",
  purpose: "PROFILE",
  spec,
});
assert.deepEqual(transformed.renditions.map(({ width, height }) => ({ width, height })), [
  { width: 48, height: 48 },
  { width: 96, height: 96 },
]);
for (const rendition of transformed.renditions) {
  assert.equal((await sharp(rendition.bytes).metadata()).format, "webp");
}

console.log(
  JSON.stringify({
    architecture: process.arch,
    handler: "dist/handler.handler",
    node: process.version,
    platform: process.platform,
    sharp: sharp.versions.sharp,
    rsvg: sharp.versions.rsvg,
    fastUri: packageRequire("fast-uri/package.json").version,
    specDigest: spec.digest,
    cardNewsSpecDigest: cardNewsSpec.digest,
    noticeSpecDigest: noticeSpec.digest,
  }),
);
