// UI logic regression tests using Node built-ins and a small DOM double.
// These tests do not replace real browser, CSS or backend integration checks.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const { webcrypto } = require('node:crypto');
const root = path.join(__dirname, '../ecom-agent-bootstrap/src/main/resources/static');
const source = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

class Element {
  constructor() { this.children = []; this.handlers = {}; this.value = ''; this.textContent = ''; this.hidden = false; }
  addEventListener(name, callback) { this.handlers[name] = callback; }
  replaceChildren(...children) { this.children = children; this.textContent = ''; }
  append(...children) { this.children.push(...children); }
  setAttribute(name, value) { this[name] = value; }
  set innerHTML(_) { throw new Error('Untrusted HTML must not be rendered'); }
}
function run(status = 'SUCCEEDED', definition = false) {
  return { id: 'run-1', status, mode: 'OFFLINE_RULES', request: { question: 'GMV', date: '2026-09-12', compareDate: '2026-09-11' },
    report: status !== 'SUCCEEDED' ? null : { title: 'ATTRIBUTION', conclusion: '<script>untrusted</script>',
      limitation: 'synthetic', sql: definition ? '' : 'SELECT approved', evidence: [],
      data: definition ? null : { current: { gmv: 2600 }, previous: { gmv: 3000 }, changeRate: -0.133333,
        contributions: [{ dimension: 'appliances', delta: -400 }], rows: [] } } };
}
function setup(options = {}) {
  const elements = Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(match => [match[1], new Element()]));
  const timers = new Map(), calls = [];
  let timerId = 0, current = options.run || run(), postAttempts = 0, modelConfig = null;
  const requirement = { definition: { id: 'tool-loop', name: '模型自主工具调用循环', layer: '执行', source: '原文 4.3', criteria: '多轮调用' },
    review: { implemented: true, extent: 'IMPLEMENTED', verification: 'AUTOMATED', rework: false, notes: '<script>不能当作 HTML</script>', version: 0 } };
  const events = options.events || ['query', 'report'].map((node, index) => ({ node, sequence: index + 1, status: 'SUCCEEDED', detail: 'ok', elapsedMs: 1 }));
  Object.assign(elements.username, { value: 'analyst' });
  Object.assign(elements.password, { value: 'test-only-password' });
  elements.question.value = 'GMV'; elements.date.value = '2026-09-12'; elements.metric.value = 'GMV';
  vm.runInNewContext(source, { document: { getElementById: id => {
    assert.ok(elements[id], 'Unknown element: ' + id); return elements[id];
  }, createElement: () => new Element() }, Option: class extends Element {
    constructor(text, value) { super(); this.textContent = text; this.value = value; }
  }, Headers, TextEncoder, AbortSignal, Intl, crypto: webcrypto, btoa,
  location: { reload() {} }, confirm: () => false,
  setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
  fetch: async (url, request) => {
    calls.push({ url, request });
    if (options.rejectLogin && url === '/api/csrf') return { ok: false, status: 401 };
    let data;
    if (url === '/api/csrf') data = { headerName: 'X-TEST-CSRF', token: 'test-token' };
    else if (url === '/api/capabilities') data = { mode: 'OFFLINE_RULES' };
    else if (url === '/actuator/health') data = { status: 'UP' };
    else if (url === '/api/model-session/clear') { modelConfig = null; data = { cleared: true }; }
    else if (url === '/api/model-session') {
      if (request.method === 'POST') {
        modelConfig = { id: 'opaque-session', provider: 'BAILIAN_BEIJING', model: 'qwen-plus', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', expiresAt: new Date(Date.now() + 1800000).toISOString() };
        data = modelConfig;
      } else data = { configured: !!modelConfig, session: modelConfig };
    }
    else if (url === '/api/requirements') data = { items: [requirement] };
    else if (url === '/api/requirements/tool-loop') {
      if (options.reviewConflict) return { ok: false, status: 409, json: async () => ({ code: 'REVIEW_VERSION_CONFLICT' }) };
      requirement.review = { ...JSON.parse(request.body), version: requirement.review.version + 1 }; data = requirement;
    }
    else if (url === '/api/runs' && request.method === 'POST') {
      if (options.failFirstSubmit && ++postAttempts === 1) throw new Error('timeout');
      data = current;
    } else if (url === '/api/runs') data = [current];
    else if (url.endsWith('/approve')) { current = run('RUNNING'); data = current; }
    else if (url.includes('/events?after=')) data = current.status === 'WAITING_FOR_REVIEW' ? [] : events.filter(e => e.sequence > Number(url.split('after=')[1]));
    else if (url === '/api/runs/run-1') {
      if (options.failPoll) throw new Error('offline');
      data = current;
    } else throw new Error('Unexpected route: ' + url);
    return { ok: true, json: async () => data };
  } });
  const submit = id => elements[id].handlers.submit({ preventDefault() {} });
  return { elements, timers, calls, submit, setRun(value) { current = value; },
    async tick() { const [id, fn] = timers.entries().next().value; timers.delete(id); await fn(); } };
}
test('login failures remain on the form; successful login hides it', async () => {
  const denied = setup({ rejectLogin: true }); await denied.submit('login-form');
  assert.match(denied.elements['login-error'].textContent, /UNAUTHORIZED/);
  assert.equal(denied.elements['login-card'].hidden, false);
  const app = setup(); await app.submit('login-form');
  assert.equal(app.elements['login-card'].hidden, true);
  assert.equal(app.elements.password.value, '');
});
test('report values are real response data and text is never inserted as HTML', async () => {
  const app = setup(); await app.submit('login-form'); await app.submit('run-form');
  assert.match(app.elements['current-gmv'].textContent, /2,600/);
  assert.match(app.elements['previous-gmv'].textContent, /3,000/);
  assert.equal(app.elements['change-rate'].textContent, '-13.33%');
  assert.equal(app.elements.conclusion.textContent, '<script>untrusted</script>');
  assert.equal(app.timers.size, 0);
  const post = app.calls.find(c => c.request.method === 'POST');
  assert.equal(post.request.headers.get('X-TEST-CSRF'), 'test-token');
  assert.ok(post.request.headers.get('Idempotency-Key'));
});
test('definition report with null data does not crash or invent money', async () => {
  const app = setup({ run: run('SUCCEEDED', true) }); await app.submit('login-form'); await app.submit('run-form');
  assert.equal(app.elements.report.hidden, false);
  assert.equal(app.elements['metrics-grid'].hidden, true);
  assert.match(app.elements['database-check'].textContent, /不查询业务表/);
});
test('approval restarts serial polling and stops after success without duplicate events', async () => {
  const app = setup({ run: run('WAITING_FOR_REVIEW') }); await app.submit('login-form'); await app.submit('run-form');
  assert.equal(app.timers.size, 0);
  await app.elements['run-actions'].children[0].onclick();
  assert.equal(app.timers.size, 1);
  app.setRun(run()); await app.tick();
  assert.equal(app.timers.size, 0);
  assert.equal(app.elements.events.children.length, 2);
  assert.equal(app.elements.report.hidden, false);
});
test('failed run shows an error and no stale report', async () => {
  const failed = run('FAILED'); failed.errorCode = 'NO_DATA';
  const app = setup({ run: failed }); await app.submit('login-form'); await app.submit('run-form');
  assert.match(app.elements['status-detail'].textContent, /NO_DATA/);
  assert.equal(app.elements.report.hidden, true);
  assert.equal(app.timers.size, 0);
});
test('network read failure stops polling and exposes retry', async () => {
  const app = setup({ failPoll: true }); await app.submit('login-form'); await app.submit('run-form');
  assert.match(app.elements['run-error'].textContent, /NETWORK_ERROR/);
  assert.equal(app.timers.size, 0);
  assert.equal(app.elements['run-actions'].children.length, 1);
});
test('retry after uncertain submission reuses its idempotency key', async () => {
  const app = setup({ failFirstSubmit: true }); await app.submit('login-form');
  await app.submit('run-form'); await app.submit('run-form');
  const posts = app.calls.filter(c => c.request.method === 'POST');
  assert.equal(posts.length, 2);
  assert.equal(posts[0].request.headers.get('Idempotency-Key'), posts[1].request.headers.get('Idempotency-Key'));
});

test('model settings clear the key input and runs contain only an opaque session id', async () => {
  const app = setup(); await app.submit('login-form');
  app.elements['model-provider'].value = 'BAILIAN_BEIJING'; app.elements['model-name'].value = 'qwen-plus';
  app.elements['model-key'].value = 'sk-test-only-not-a-real-key'; app.elements['model-consent'].checked = true;
  await app.submit('model-form');
  assert.equal(app.elements['model-key'].value, '');
  assert.equal(app.elements['execution-mode'].value, 'multi');
  assert.match(app.elements['model-status'].textContent, /尚不代表联通验证/);
  assert.doesNotMatch(app.elements['model-status'].textContent, /sk-test/);
  await app.submit('run-form');
  const body = app.calls.find(c => c.url === '/api/runs' && c.request.method === 'POST').request.body;
  assert.equal(JSON.parse(body).modelSessionId, 'opaque-session'); assert.doesNotMatch(body, /sk-test/);
  assert.equal(JSON.parse(body).executionMode, 'LLM_MULTI_AGENT');
  await app.elements['model-clear'].onclick();
  assert.equal(app.elements['tool-mode-option'].disabled, true);
  assert.equal(app.elements['execution-mode'].value, 'default');
});
function descendants(element) { return [element, ...element.children.flatMap(descendants)]; }
test('acceptance board saves implementation and rework separately with a version', async () => {
  const app = setup(); await app.submit('login-form'); await app.elements['requirements-refresh'].onclick();
  const controls = descendants(app.elements['requirements-rows']);
  const field = suffix => controls.find(c => c['aria-label'] === '模型自主工具调用循环：' + suffix);
  assert.equal(field('验收备注').value, '<script>不能当作 HTML</script>');
  field('已实现').checked = false; field('已实现').onchange();
  assert.equal(field('代码完成度').value, 'PARTIAL');
  field('需要打回更新').checked = true; field('需要打回更新').onchange();
  field('验收备注').value = '需要验证外部模型'; field('验收备注').oninput();
  await field('保存此项').onclick();
  const request = app.calls.find(c => c.url === '/api/requirements/tool-loop').request;
  assert.deepEqual(JSON.parse(request.body), { implemented: false, extent: 'PARTIAL', verification: 'AUTOMATED', rework: true, notes: '需要验证外部模型', version: 0 });
  assert.equal(request.headers.get('X-TEST-CSRF'), 'test-token');
  assert.match(app.elements['requirements-summary'].textContent, /未保存 0/);
});
test('stale acceptance saves preserve the unsaved note and display conflict', async () => {
  const app = setup({ reviewConflict: true }); await app.submit('login-form'); await app.elements['requirements-refresh'].onclick();
  const controls = descendants(app.elements['requirements-rows']);
  const notes = controls.find(c => c['aria-label']?.endsWith('：验收备注'));
  notes.value = '必须保留的本地修改'; notes.oninput();
  await controls.find(c => c['aria-label']?.endsWith('：保存此项')).onclick();
  assert.equal(notes.value, '必须保留的本地修改');
  assert.ok(controls.some(c => /记录已被另一页面更新/.test(c.textContent)));
  assert.match(app.elements['requirements-summary'].textContent, /未保存 1/);
  const before = app.calls.length; await app.elements['requirements-refresh'].onclick();
  assert.equal(app.calls.length, before, 'declining discard must not reload');
});

test('model prose is displayed separately as inert text', async () => {
  const result = run(); result.mode = 'LLM_TOOL_CALLING'; result.report.modelAnswer = '<img src=x onerror=bad()>解释';
  const app = setup({ run: result }); await app.submit('login-form'); await app.submit('run-form');
  assert.equal(app.elements['model-answer-card'].hidden, false);
  assert.equal(app.elements['model-answer'].textContent, result.report.modelAnswer);
  assert.match(app.elements['current-gmv'].textContent, /2,600/);
});

test('multi-agent panel shows observed participants and real handoffs only', async () => {
  const result = run(); result.mode = 'LLM_MULTI_AGENT';
  const events = [
    { node: 'intent_agent', status: 'STARTED', detail: 'route' },
    { node: 'supervisor', status: 'ROUTED', detail: 'ATTRIBUTION' },
    { node: 'analysis_agent', status: 'STARTED', detail: 'request data' },
    { node: 'query_agent', status: 'STARTED', detail: 'query' },
    { node: 'handoff', status: 'SUCCEEDED', detail: 'QueryAgent → AnalysisAgent：packet-1' },
    { node: 'report', status: 'SUCCEEDED', detail: 'complete' }
  ].map((e, i) => ({ ...e, sequence: i + 1, elapsedMs: 0 }));
  const app = setup({ run: result, events }); await app.submit('login-form'); await app.submit('run-form');
  assert.equal(app.elements['collaboration-card'].hidden, false);
  assert.equal(app.elements['agent-participants'].children.length, 4);
  assert.equal(app.elements['agent-handoffs'].children.length, 1);
  assert.match(app.elements['agent-handoffs'].children[0].textContent, /packet-1/);
  assert.ok(app.elements['agent-participants'].children.every(c => !c.textContent.includes('Ops')));
});
test('interrupted multi-agent task has no misleading resume action', async () => {
  const result = run('INTERRUPTED'); result.mode = 'LLM_MULTI_AGENT';
  const app = setup({ run: result }); await app.submit('login-form'); await app.submit('run-form');
  assert.ok(app.elements['run-actions'].children.every(c => c.textContent !== '从检查点恢复'));
});
