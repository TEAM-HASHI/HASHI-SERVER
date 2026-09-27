import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const workerPath = ".github/workflows/ci-image-transform-worker.yml";
const infraPath = ".github/workflows/ci-image-pipeline-infra.yml";
const readWorkflow = (path) => fs.readFileSync(path, "utf8").replaceAll("\r\n", "\n");
const worker = readWorkflow(workerPath);
const infra = readWorkflow(infraPath);

// Inspect each event separately: a path in pull_request must not mask a missing push path.
// These checks deliberately require the repository's explicit, block-style path lists.
function eventPaths(workflow, event) {
  const triggers = workflow.match(/^on:\n([\s\S]*?)(?=^\S)/m)?.[1];
  assert.ok(triggers, "Expected an explicit on: block");
  const section = triggers.match(new RegExp(`^  ${event}:\\n([\\s\\S]*?)(?=^  \\S|$(?![\\s\\S]))`, "m"))?.[1];
  assert.ok(section, `Missing ${event} event`);
  const paths = section.match(/^    paths:\n((?:      - [^\n]+\n)+)/m)?.[1];
  assert.ok(paths, `Missing explicit ${event} paths`);
  return paths.trim().split("\n").map((line) => line.trim().replace(/^- ["']?/, "").replace(/["']$/, ""));
}

// Only inspect executable run values, not step names or comments.
function runCommands(workflow) {
  return [...workflow.matchAll(/^        run: ([^\n]*)\n((?:          [^\n]*\n|\n)*)/gm)]
    .map(([, inline, block]) => /^[|>][-+]?$/.test(inline) ? block : inline)
    .join("\n")
    .split("\n")
    .filter((line) => !line.trimStart().startsWith("#"))
    .join("\n");
}

for (const event of ["pull_request", "push"]) {
  for (const [name, workflow] of [["worker", worker], ["infra", infra]]) {
    test(`${name} ${event}는 워커와 명세 및 워커 검증 워크플로 변경을 감지한다`, () => {
      const paths = eventPaths(workflow, event);
      for (const path of ["worker/image-transform/**", "media-specs/**", workerPath]) {
        assert.ok(paths.includes(path), `${name} ${event} must include ${path}`);
      }
    });
  }

  test(`인프라 ${event}는 인프라와 배포 변경 감지를 유지한다`, () => {
    const paths = eventPaths(infra, event);
    for (const path of [infraPath, ".github/workflows/deploy-image-pipeline.yml", "infra/media/**"]) {
      assert.ok(paths.includes(path), `${event} must include ${path}`);
    }
  });
}

test("워커 검증에서 워커 테스트를 실행하고 인프라 검증에서는 중복 실행하지 않는다", () => {
  const testCommand = /\bnpm\s+(?:test|t|run\s+test)\b/;
  assert.match(runCommands(worker), testCommand);
  assert.doesNotMatch(runCommands(infra), testCommand);
});

test("워커 검증은 타입 검사와 패키징 및 패키지 기본 동작 검증을 유지한다", () => {
  const commands = runCommands(worker);
  assert.match(commands, /\bnpm run typecheck\b/);
  assert.match(commands, /\bnpm run package:lambda\b/);
  assert.match(commands, /node scripts\/verify-package\.mjs artifacts\/smoke/);
});

test("경로 추출은 다른 이벤트의 감지 경로를 포함하지 않는다", () => {
  const changed = infra.replace(`      - "${workerPath}"\n`, "");
  assert.ok(!eventPaths(changed, "pull_request").includes(workerPath));
  assert.ok(eventPaths(changed, "push").includes(workerPath));
});

test("실행 명령 추출은 주석을 제외하고 블록 명령을 포함한다", () => {
  const commands = runCommands("        run: |\n          # npm test\n          npm ci\n          npm run test\n");
  assert.doesNotMatch(commands, /# npm test/);
  assert.match(commands, /npm ci/);
  assert.match(commands, /npm run test/);
});

test("인프라 검증은 스택 빌드 전에 로컬 패키지 검증 순서를 유지한다", () => {
  const commands = runCommands(infra);
  const required = [
    "npm ci",
    "npm run package:lambda",
    "node scripts/verify-package.mjs artifacts/package",
    "sam validate --lint",
    "sam build",
  ];
  let previous = -1;
  for (const command of required) {
    const position = commands.indexOf(command);
    assert.ok(position > previous, `${command} must be present in build order`);
    previous = position;
  }
  assert.match(commands, /--template-file infra\/media\/template\.yaml/);
  assert.match(commands, /--build-dir \.aws-sam\/media-build/);
});

test("인프라 검증은 검증 책임과 변경 감지에 관한 테스트를 실행한다", () => {
  assert.match(runCommands(infra), /node --test infra\/media\/scripts\/check-ci-triggers\.test\.mjs/);
});
