import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { validateProdRequest } from './validate-prod-request.mjs';

const valid = {
  GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REF: 'refs/heads/main',
  GITHUB_SHA: 'a'.repeat(40), EXPECTED_SHA: 'a'.repeat(40), TRANSITION_VERIFIED: 'true',
};
test('explicit main request for exact SHA passes', () => assert.doesNotThrow(() => validateProdRequest(valid)));
for (const [label, patch] of [
  ['push', { GITHUB_EVENT_NAME: 'push' }],
  ['pull request', { GITHUB_EVENT_NAME: 'pull_request' }],
  ['develop', { GITHUB_REF: 'refs/heads/develop' }],
  ['tag', { GITHUB_REF: 'refs/tags/main' }],
  ['missing confirmation', { TRANSITION_VERIFIED: undefined }],
  ['false confirmation', { TRANSITION_VERIFIED: 'false' }],
  ['missing SHA', { EXPECTED_SHA: undefined }],
  ['short SHA', { EXPECTED_SHA: 'aaaaaaa' }],
  ['different SHA', { EXPECTED_SHA: 'b'.repeat(40) }],
  ['non-hex SHA', { EXPECTED_SHA: 'z'.repeat(40) }],
]) {
  test(`reject ${label}`, () => assert.throws(() => validateProdRequest({ ...valid, ...patch })));
}
test('CLI fails closed and returns nonzero without printing input values', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./validate-prod-request.mjs', import.meta.url))], {
    env: { ...process.env, ...valid, EXPECTED_SHA: 'private-input-not-for-logs' }, encoding: 'utf8',
  });
  assert.equal(result.status, 1);
  assert.doesNotMatch(result.stdout + result.stderr, /private-input-not-for-logs/);
});
test('workflow wires guard before build and credentials and has no push trigger', () => {
  const workflow = readFileSync(new URL('../../.github/workflows/cd-prod.yml', import.meta.url), 'utf8');
  assert.doesNotMatch(workflow, /^\s+push:\s*$/m);
  assert.match(workflow, /if: github.event_name == 'workflow_dispatch' && github.ref == 'refs\/heads\/main'/);
  assert.match(workflow, /EXPECTED_SHA: \$\{\{ inputs.expected_sha \}\}/);
  assert.match(workflow, /TRANSITION_VERIFIED: \$\{\{ inputs.transition_verified \}\}/);
  const guard = workflow.indexOf('run: node scripts/deploy/validate-prod-request.mjs');
  assert.ok(guard > 0 && guard < workflow.indexOf('- name: Build with Gradle'));
  assert.ok(guard < workflow.indexOf('- name: Log in to Docker Hub'));
});
