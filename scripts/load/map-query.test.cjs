const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const sourcePath = process.argv[2] || require('node:path').join(__dirname, 'map-query.js');
const source = fs.readFileSync(sourcePath, 'utf8').replace(/^import .*;\r?\n/gm, '')
    .replace('export const options', 'const options')
    .replace('export default function ()', 'function runIteration()') + '\nrunIteration();';
function valid(start = 1) {
    return { content: Array.from({ length: 10 }, (_, i) => ({ restaurantId: start + i })),
        hasNext: true, nextCursor: 'cursor', querySessionId: 'session' };
}
function run(name, response, expectedFailure) {
    const failures = [], checks = [];
    let calls = 0, sleeps = 0;
    vm.runInNewContext(source, {
        __ENV: { BASE_URL: 'http://127.0.0.1:12345' },
        http: { get() { return response(calls++); } },
        check(value, rules) {
            const passed = Object.values(rules).every(rule => rule(value)); checks.push(passed); return passed;
        },
        sleep() { sleeps++; },
        Counter: class { add() {} },
        Trend: class { add() {} },
        Rate: class { add(value) { failures.push(value); } },
    });
    assert.equal(failures.includes(true), expectedFailure, name + ' failure metric');
    assert.equal(checks.includes(false), expectedFailure, name + ' failed check');
    assert.equal(sleeps, 1, name + ' bounded request pacing');
    console.log('PASS ' + name);
}
function response(data) { return { status: 200, timings: { duration: 1 }, json: () => data }; }
run('valid mixed iteration', call => response(valid(call === 1 || call === 2 ? 11 : 1)), false);
for (const [name, data] of [
    ['ten null cards', { ...valid(), content: Array(10).fill(null) }],
    ['array-like content', { ...valid(), content: { length: 10 } }],
    ['null data', null],
    ['missing cursor', { ...valid(), nextCursor: undefined }],
    ['missing session', { ...valid(), querySessionId: undefined }],
    ['blank cursor', { ...valid(), nextCursor: ' ' }],
    ['truthy non-boolean hasNext', { ...valid(), hasNext: 'true' }],
    ['string ID', { ...valid(), content: Array(10).fill({ restaurantId: '1' }) }],
    ['nonpositive ID', { ...valid(), content: Array(10).fill({ restaurantId: 0 }) }],
    ['unsafe integer ID', { ...valid(), content: Array(10).fill({ restaurantId: Number.MAX_SAFE_INTEGER + 1 }) }],
]) run(name, () => response(data), true);
run('invalid JSON', () => ({ status: 200, timings: { duration: 1 }, json() { throw new SyntaxError(); } }), true);
run('unexpected request exception', () => { throw new TypeError(); }, true);
run('HTTP failure', () => ({ status: 503, timings: { duration: 1 } }), true);
run('HTTP caller throttled', () => ({ status: 429, timings: { duration: 1 } }), true);
const configure = fs.readFileSync(sourcePath, 'utf8').replace(/^import .*;\r?\n/gm, '')
    .replace('export const options', 'globalThis.options')
    .replace('export default function ()', 'function runIteration()');
for (const profile of ['smoke', 'staged', 'ttl']) {
    const context = { __ENV: { BASE_URL: 'http://127.0.0.1:12345', MAP_LOAD_PROFILE: profile },
        Trend: class {}, Rate: class {}, Counter: class {} };
    vm.runInNewContext(configure, context);
    const stages = context.options.scenarios.browsing.stages;
    assert.equal(stages.reduce((seconds, stage) => seconds + parseInt(stage.duration), 0), profile === 'smoke' ? 90 : 300);
    assert.equal(Math.max(...stages.map(stage => stage.target)), profile === 'smoke' ? 2 : 20);
    assert.equal(context.options.thresholds.http_req_failed[0], 'rate<0.01');
}
for (const env of [{ BASE_URL: 'https://production.example' },
        { BASE_URL: 'http://127.0.0.1:12345', MAP_LOAD_PROFILE: 'unbounded' }]) {
    assert.throws(() => vm.runInNewContext(configure, {__ENV: env}));
}
console.log('15 response cases + 3 bounded profiles + 2 configuration rejections passed');
