const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../ecom-agent-bootstrap/src/main/resources/static/stream.js'), 'utf8');

function runtime(fetch) {
  const context = { TextDecoder, fetch };
  vm.runInNewContext(source, context);
  return context.EcomStream;
}

test('a network chunk containing many valid small events is not one oversized event', () => {
  const messages = [], stream = runtime(), push = stream.parser(event => messages.push(event));
  const chunk = Array.from({ length: 1000 }, (_, i) =>
    `id: ${i + 1}\nevent: node\ndata: ${JSON.stringify({ sequence: i + 1, detail: 'x'.repeat(150) })}\n\n`).join('');
  assert.ok(chunk.length > 131072);
  push(chunk);
  assert.equal(messages.length, 1000);
  assert.equal(messages[999].data.sequence, 1000);
});

test('an oversized complete frame remains rejected even when it has a delimiter', () => {
  const push = runtime().parser(() => {});
  assert.throws(() => push(`data: ${JSON.stringify('x'.repeat(131073))}\n\n`), /STREAM_EVENT_TOO_LARGE/);
});

test('connect cancels its network body after a consumer error and keeps auth out of the URL', async () => {
  let cancelled = false, observed;
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('id: 1\nevent: node\ndata: {"sequence":1}\n\n'));
    },
    cancel() { cancelled = true; }
  });
  const stream = runtime(async (url, options) => {
    observed = { url, options };
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
  });
  await assert.rejects(stream.connect({ url: '/api/runs/synthetic/stream?after=0',
    auth: 'Basic synthetic-test-only', signal: new AbortController().signal,
    onEvent() { throw new Error('SYNTHETIC_CONSUMER_FAILURE'); }
  }), /SYNTHETIC_CONSUMER_FAILURE/);
  assert.equal(cancelled, true, 'releaseLock alone does not cancel an active network body');
  assert.equal(observed.url, '/api/runs/synthetic/stream?after=0');
  assert.equal(observed.options.headers.Authorization, 'Basic synthetic-test-only');
  assert.equal(observed.options.credentials, 'same-origin');
});
