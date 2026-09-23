const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const context={TextDecoder};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../ecom-agent-bootstrap/src/main/resources/static/stream.js'),'utf8'),context);
test('SSE handles split frames and CRLF without losing IDs',()=>{
  const output=[],push=context.EcomStream.parser(e=>output.push(e));
  push('id: 7\r\nevent: no');push('de\r\ndata: {"sequence":7}\r');push('\n\r\n');
  assert.equal(output.length,1);assert.equal(output[0].id,'7');assert.equal(output[0].data.sequence,7);
});
test('SSE supports heartbeat comments and multiple frames',()=>{
  const output=[],push=context.EcomStream.parser(e=>output.push(e));
  push(':keepalive\n\nevent: status\ndata: "RUNNING"\n\nevent: status\ndata: "SUCCEEDED"\n\n');
  assert.equal(output.length,2);assert.equal(output[1].data,'SUCCEEDED');
});
test('SSE malformed and oversized data fail closed',()=>{
  assert.throws(()=>context.EcomStream.parser(()=>{})('data: not-json\n\n'));
  assert.throws(()=>context.EcomStream.parser(()=>{})('x'.repeat(131073)),/STREAM_EVENT_TOO_LARGE/);
});
test('SSE preserves a partial next frame',()=>{
  const output=[],push=context.EcomStream.parser(e=>output.push(e));
  push('data: 1\n\ndata:');assert.equal(output.length,1);push(' 2\n\n');assert.equal(output[1].data,2);
});
