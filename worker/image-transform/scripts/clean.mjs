import { rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptsDirectory = dirname(fileURLToPath(import.meta.url));

rmSync(resolve(scriptsDirectory, "..", "dist"), {
  force: true,
  recursive: true,
});
