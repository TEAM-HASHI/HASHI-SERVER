import { pathToFileURL } from "node:url";

export const PROTECTED_RENDITION_PREFIX = "media/renditions/";

function ruleHasTagConstraint(rule) {
  const filter = rule.Filter;
  return Boolean(filter?.Tag || (Array.isArray(filter?.And?.Tags) && filter.And.Tags.length > 0));
}

function rulePrefix(rule) {
  if (typeof rule.Filter?.Prefix === "string") {
    return rule.Filter.Prefix;
  }
  if (typeof rule.Filter?.And?.Prefix === "string") {
    return rule.Filter.And.Prefix;
  }
  if (typeof rule.Prefix === "string") {
    return rule.Prefix;
  }
  return "";
}

function prefixesOverlap(left, right) {
  return left.startsWith(right) || right.startsWith(left);
}

function changesCurrentObjects(rule) {
  const expiration = rule.Expiration;
  const expiresCurrentObjects = Boolean(expiration?.Date || expiration?.Days !== undefined);
  const transitionsCurrentObjects =
    Array.isArray(rule.Transitions) && rule.Transitions.length > 0;
  return expiresCurrentObjects || transitionsCurrentObjects;
}

export function findConflictingRules(
  configuration,
  protectedPrefix = PROTECTED_RENDITION_PREFIX,
) {
  const rules = Array.isArray(configuration?.Rules) ? configuration.Rules : [];

  return rules
    .filter((rule) => rule.Status === "Enabled")
    .filter((rule) => !ruleHasTagConstraint(rule))
    .filter(changesCurrentObjects)
    .filter((rule) => prefixesOverlap(rulePrefix(rule), protectedPrefix))
    .map((rule) => ({
      id: rule.ID ?? "<unnamed>",
      prefix: rulePrefix(rule),
    }));
}

async function readStdin() {
  let input = "";
  for await (const chunk of process.stdin) {
    input += chunk;
  }
  return input;
}

async function main() {
  const input = await readStdin();
  const configuration = JSON.parse(input);
  const conflicts = findConflictingRules(configuration);

  if (conflicts.length === 0) {
    process.stdout.write("Delivery bucket lifecycle is compatible with media renditions.\n");
    return;
  }

  const ruleSummary = conflicts
    .map(({ id, prefix }) => `${id} (prefix: ${prefix || "<all objects>"})`)
    .join(", ");
  throw new Error(
    `Lifecycle rules can expire or transition ${PROTECTED_RENDITION_PREFIX}: ${ruleSummary}`,
  );
}

const entrypoint = process.argv[1] ? pathToFileURL(process.argv[1]).href : undefined;
if (entrypoint === import.meta.url) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
