import { pathToFileURL } from 'node:url';

export function validateProdRequest(env) {
  if (env.GITHUB_EVENT_NAME !== 'workflow_dispatch' || env.GITHUB_REF !== 'refs/heads/main') {
    throw new Error('Production deployment requires a manual request on main.');
  }
  if (env.TRANSITION_VERIFIED !== 'true') {
    throw new Error('Transition and recovery verification must be confirmed.');
  }
  if (!/^[a-f0-9]{40}$/.test(env.EXPECTED_SHA ?? '')
      || env.EXPECTED_SHA !== env.GITHUB_SHA) {
    throw new Error('The requested release SHA does not match this workflow commit.');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    validateProdRequest(process.env);
    console.log('Production deployment request validated.');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
