import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { renderRuntimeBoundaries, validateRuntimeBoundaryDocument } from "./check-dev-runtime-boundaries.mjs";

const DAY = 86_400_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const fail = () => { throw new Error("DEV cleanup trial input is not within the approved scope."); };
const canonical = (v) => Array.isArray(v) ? v.map(canonical) : v && typeof v === "object"
  ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canonical(v[k])])) : v;
const hash = (v) => createHash("sha256").update(JSON.stringify(canonical(v))).digest("hex");

// Offline preparation only. The caller must separately verify live IAM, S3 and DB state.
export function prepareDevCleanupTrial(config, manifest, actualBoundary, now = new Date()) {
  const time = now.getTime();
  if (!Number.isFinite(time) || config.environment !== "dev" || config.region !== "ap-northeast-2" ||
      !/^[0-9]{12}$/.test(config.accountId ?? "") || config.springRoleName !== "hashi-dev-ec2-role" ||
      !/^hashi-dev-media-pipeline-originalimagebucket-[a-z0-9]+$/.test(config.originalBucket ?? "") ||
      !/^hashi-dev-[a-z0-9-]+$/.test(config.deliveryBucket ?? "") ||
      config.deliveryBucket === config.originalBucket || !Number.isInteger(config.validForMinutes) ||
      config.validForMinutes < 5 || config.validForMinutes > 60) fail();
  const expected = renderRuntimeBoundaries(config.accountId, config.region, config.deliveryBucket).SpringRuntimeBoundary;
  validateRuntimeBoundaryDocument(actualBoundary, expected.document);
  if (!UUID.test(manifest.asset_id ?? "") || manifest.complete !== true ||
      manifest.content_is_synthetic_text_not_image !== true || manifest.db_absent_after !== true ||
      !Array.isArray(manifest.objects) || manifest.objects.length !== 2) fail();
  const locations = new Set();
  const expiresAt = new Date(time + config.validForMinutes * 60_000).toISOString();
  const statements = manifest.objects.map((object) => {
    if (!["original", "rendition"].includes(object.location) || locations.has(object.location)) fail();
    locations.add(object.location);
    const expectedBucket = object.location === "original" ? config.originalBucket : config.deliveryBucket;
    const originalKey = `media/originals/${manifest.asset_id}/original`;
    const renditionPattern = new RegExp(`^media/renditions/${manifest.asset_id}/v[1-9][0-9]*/[a-z][a-z0-9_]*/[1-9][0-9]*\\.webp$`);
    const age = time - Date.parse(object.last_modified_utc);
    if (object.bucket !== expectedBucket || object.verified !== true ||
        (object.location === "original" ? object.key !== originalKey : !renditionPattern.test(object.key ?? "")) ||
        typeof object.version_id !== "string" || object.version_id === "null" ||
        !/^[A-Za-z0-9+/_=.-]{1,1024}$/.test(object.version_id) || !Number.isFinite(age) || age < 7 * DAY) fail();
    return { Sid: object.location === "original" ? "DeleteTrialOriginalVersion" : "DeleteTrialRenditionVersion",
      Effect: "Allow", Action: ["s3:DeleteObjectVersion"], Resource: `arn:aws:s3:::${object.bucket}/${object.key}`,
      Condition: { StringEquals: { "s3:VersionId": object.version_id, "s3:ResourceAccount": config.accountId },
        DateGreaterThanEquals: { "aws:CurrentTime": now.toISOString() },
        DateLessThan: { "aws:CurrentTime": expiresAt } } };
  });
  const grant = { Version: "2012-10-17", Statement: statements };
  return { environment: "dev", roleName: config.springRoleName, boundaryArn: expected.arn,
    expiresAt, originalBoundarySha256: hash(expected.document),
    restoreBoundary: expected.document, inlinePolicyName: "hashi-dev-media-exact-fixture-trial",
    grant, trialBoundary: { ...expected.document, Statement: [...expected.document.Statement, ...statements] },
    warning: "Preparation only; live checks, reviewed application, receipt and rollback are required. Existing legacy grants are unchanged." };
}

const isCli = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCli) {
  try {
    const [configPath, manifestPath, boundaryPath, outputPath] = process.argv.slice(2);
    if (!configPath || !manifestPath || !boundaryPath || !outputPath || process.argv.length !== 6) fail();
    const read = (p) => JSON.parse(fs.readFileSync(p, "utf8").replace(/^\uFEFF/, ""));
    const result = prepareDevCleanupTrial(read(configPath), read(manifestPath), read(boundaryPath));
    fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { flag: "wx", mode: 0o600 });
    console.log("Prepared a private DEV trial bundle; no AWS request or deletion was performed.");
  } catch {
    console.error("DEV trial preparation failed. Check scope, age, reviewed boundary and a new private output path.");
    process.exitCode = 1;
  }
}
