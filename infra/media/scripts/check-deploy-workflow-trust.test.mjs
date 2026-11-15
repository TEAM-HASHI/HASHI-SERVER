import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import path from "node:path";
import { spawnSync } from "node:child_process";

import { validateDeployWorkflowTrust } from "./check-deploy-workflow-trust.mjs";

const workflow = fs
  .readFileSync(".github/workflows/deploy-image-pipeline.yml", "utf8")
  .replaceAll("\r\n", "\n");

test("accepts the reviewed dev-only AWS trust boundary", () => {
  assert.doesNotThrow(() => validateDeployWorkflowTrust(workflow));
});

// Run the workflow's actual pre-checkout guard, not a second implementation of its rules.
const guard = workflow.split("          set -euo pipefail\n")[1]
  .split("\n      - name: Checkout source code")[0]
  .split("\n").map((line) => line.replace(/^          /, "")).join("\n");
function findWindowsBash({findPaths, exists, probe, env}) {
  const candidates = [...findPaths("bash")];
  for (const git of findPaths("git")) {
    const directory = path.win32.dirname(git);
    for (const relative of ["bash.exe", "../bin/bash.exe", "../usr/bin/bash.exe", "../../bin/bash.exe", "../../usr/bin/bash.exe"]) {
      candidates.push(path.win32.resolve(directory, relative));
    }
  }
  for (const root of [env.ProgramFiles, env["ProgramFiles(x86)"], env.LOCALAPPDATA && path.win32.join(env.LOCALAPPDATA, "Programs")]) {
    if (root) candidates.push(path.win32.join(root, "Git", "bin", "bash.exe"));
  }
  for (const candidate of new Set(candidates)) {
    // Do not launch the Windows WSL launcher or a WindowsApps execution alias.
    if (/\\(?:System32|WindowsApps)\\/i.test(candidate) || !exists(candidate)) continue;
    if (probe(candidate)) return candidate;
  }
  throw new Error("A working Git Bash is required to test the workflow guard on Windows. Install Git for Windows or add its bash.exe to PATH.");
}

const bash = process.platform === "win32" ? findWindowsBash({
  findPaths(command) {
    // Read PATH directly so non-ASCII installation paths do not depend on where.exe's code page.
    return (process.env.PATH || "").split(path.delimiter).filter(Boolean)
      .map((directory) => path.win32.join(directory.replace(/^"|"$/g, ""), `${command}.exe`))
      .filter((candidate) => fs.existsSync(candidate));
  },
  exists: fs.existsSync,
  probe(candidate) {
    const result = spawnSync(candidate, ["--noprofile", "--norc", "-c", '[[ -n "$BASH_VERSION" && ( "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ) ]]'], {
      encoding:"utf8", windowsHide:true, timeout:5000,
    });
    return !result.error && result.status === 0;
  },
  env: process.env,
}) : "bash";

test("finds PATH Bash even when git is a shim", () => {
  assert.equal(findWindowsBash({
    findPaths: (command) => command === "bash" ? ["C:\\tools\\bash.exe"] : ["C:\\shims\\git.exe"],
    exists: (candidate) => candidate === "C:\\tools\\bash.exe", probe: () => true, env: {},
  }), "C:\\tools\\bash.exe");
});

test("tries every portable Git path after an unusable Bash", () => {
  const probed = [];
  assert.equal(findWindowsBash({
    findPaths: (command) => command === "bash" ? ["C:\\broken\\bash.exe"] : ["C:\\shims\\git.exe", "D:\\PortableGit\\cmd\\git.exe"],
    exists: (candidate) => ["C:\\broken\\bash.exe", "D:\\PortableGit\\bin\\bash.exe"].includes(candidate),
    probe: (candidate) => { probed.push(candidate); return candidate.startsWith("D:"); }, env: {},
  }), "D:\\PortableGit\\bin\\bash.exe");
  assert.equal(probed.length, 2);
});

test("ignores WSL aliases and falls back to a known Git installation", () => {
  const probed = [];
  assert.equal(findWindowsBash({
    findPaths: (command) => command === "bash" ? ["C:\\Windows\\System32\\bash.exe", "C:\\Users\\test\\AppData\\Local\\Microsoft\\WindowsApps\\bash.exe"] : [],
    exists: () => true, probe: (candidate) => { probed.push(candidate); return true; },
    env: {ProgramFiles:"C:\\Program Files"},
  }), "C:\\Program Files\\Git\\bin\\bash.exe");
  assert.deepEqual(probed, ["C:\\Program Files\\Git\\bin\\bash.exe"]);
});

test("fails explicitly rather than skipping guards when Bash is unavailable", () => {
  assert.throws(() => findWindowsBash({findPaths: () => [], exists: () => false, probe: () => false, env: {}}), /working Git Bash is required/);
});

for (const [target, ref, confirmation, allowed] of [
  ["dev", "refs/heads/develop", "", true],
  ["dev", "refs/heads/release/1.3.0", "prepare-prod-artifact", false],
  ["dev", "refs/heads/main", "", false],
  ["prod", "refs/heads/main", "prepare-prod-artifact", true],
  ["prod", "refs/heads/release/1.3.0", "prepare-prod-artifact", true],
  ["prod", "refs/heads/release/1.3.0", "", false],
  ["prod", "refs/heads/develop", "prepare-prod-artifact", false],
  ["prod", "refs/tags/release/1.3.0", "prepare-prod-artifact", false],
  ["prod", "refs/pull/123/merge", "prepare-prod-artifact", false],
  ["prod", "refs/heads/release/1.3.0/unsafe", "prepare-prod-artifact", false],
  ["prod", "refs/heads/release/1x3x0", "prepare-prod-artifact", false],
  ["prod", "refs/heads/release/next", "prepare-prod-artifact", false],
  ["other", "refs/heads/main", "prepare-prod-artifact", false],
]) {
  test(`operation guard ${allowed ? "allows" : "rejects"} ${target} ${ref} ${confirmation || "no confirmation"}`, () => {
    const result = spawnSync(bash, ["-c", `set -euo pipefail\n${guard}`], {
      env: {...process.env, TARGET:target, DEPLOYMENT_REF:ref, PRODUCTION_CONFIRMATION:confirmation},
      encoding:"utf8", windowsHide:true,
    });
    assert.ifError(result.error);
    assert.equal(result.status, allowed ? 0 : 1, result.stdout + result.stderr);
  });
}

test("rejects broadening the production artifact branch guard", () => {
  const changed = workflow.replace("^refs/heads/release/[0-9]+\\.[0-9]+\\.[0-9]+$", "^refs/heads/.*$");
  assert.throws(() => validateDeployWorkflowTrust(changed), /versioned release ref/);
});

test("rejects OIDC permission in the build job", () => {
  const changed = workflow.replace(
    "    permissions:\n      contents: read\n    outputs:",
    "    permissions:\n      contents: read\n      id-token: write\n    outputs:",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /Build job must not request/);
});

test("rejects a production AWS repository variable", () => {
  const changed = workflow.replace(
    "          MEDIA_DEV_AWS_ACCOUNT_ID:",
    "          MEDIA_PROD_AWS_ACCOUNT_ID: unsafe\n          MEDIA_DEV_AWS_ACCOUNT_ID:",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /Production AWS configuration/);
});

test("rejects recomputing the artifact name in the deploy job", () => {
  const changed = workflow.replace(
    "name: ${{ needs.build.outputs.artifact_name }}",
    "name: image-transform-${{ github.sha }}-${{ github.run_attempt }}",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /exported by the build job/);
});

test("rejects handing off a production artifact without a package smoke test", () => {
  const changed = workflow.replace("          node scripts/verify-package.mjs artifacts/package\n", "");

  assert.throws(() => validateDeployWorkflowTrust(changed), /before handoff/);
});

test("rejects executing worker artifact code in the OIDC-capable deploy job", () => {
  const changed = workflow.replace(
    "          test -f worker/image-transform/artifacts/package/package.json\n",
    "          node worker/image-transform/scripts/verify-package.mjs worker/image-transform/artifacts/package\n",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /cannot execute worker artifact code/);
});

test("rejects creating a production change set in GitHub Actions", () => {
  const changed = workflow.replace(
    '          sam deploy "${deploy_args[@]}"',
    '          sam deploy "${deploy_args[@]}" --no-execute-changeset',
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /cannot create a production/);
});
