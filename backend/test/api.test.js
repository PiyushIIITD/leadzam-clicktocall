'use strict';

const { test, beforeEach, after } = require('node:test');
const assert = require('node:assert/strict');
const { createApp } = require('../src/app');
const { createStore } = require('../src/store');
const { DialError } = require('../src/fcm');
const { normalizePhone } = require('../src/validate');

const TOKEN = 'x'.repeat(60);
let server, base, store, sent, sendImpl;

function fakeSender() {
  return {
    isConfigured: () => true,
    send: (token, data) => sendImpl(token, data),
  };
}

beforeEach(async () => {
  if (server) await new Promise((r) => server.close(r));
  store = createStore(); // in-memory only
  sent = [];
  sendImpl = async (token, data) => {
    sent.push({ token, data });
    return { messageId: 'projects/p/messages/1' };
  };
  const app = createApp({ sender: fakeSender(), store });
  server = await new Promise((r) => {
    const s = app.listen(0, () => r(s));
  });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server && server.close());

const post = (path, body, raw) =>
  fetch(base + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: raw !== undefined ? raw : JSON.stringify(body),
  });

test('normalizePhone accepts common formats and rejects junk', () => {
  assert.equal(normalizePhone('+91 98765 43210'), '+919876543210');
  assert.equal(normalizePhone('(022) 2345-6789'), '02223456789');
  for (const bad of ['', '   ', 'abc', '12345', '+91+9876543210', '1'.repeat(16), null, 42, '98765x43210']) {
    assert.equal(normalizePhone(bad), null, `should reject ${JSON.stringify(bad)}`);
  }
});

test('dial: rejects empty / invalid phone with 400 and sends nothing', async () => {
  await post('/api/register', { token: TOKEN });
  for (const phoneNumber of ['', '   ', 'hello', undefined]) {
    const res = await post('/api/dial', { phoneNumber, contactName: 'A' });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, 'INVALID_PHONE');
  }
  assert.equal(sent.length, 0);
});

test('dial: malformed JSON gives 400', async () => {
  const res = await post('/api/dial', null, '{not json');
  assert.equal(res.status, 400);
});

test('dial: 409 NO_DEVICE when no token registered', async () => {
  const res = await post('/api/dial', { phoneNumber: '+919876543210' });
  assert.equal(res.status, 409);
  assert.equal((await res.json()).code, 'NO_DEVICE');
});

test('dial: happy path sends a data message and returns 202 + requestId', async () => {
  await post('/api/register', { token: TOKEN, deviceName: 'Pixel' });
  const res = await post('/api/dial', { phoneNumber: '+91 98765 43210', contactName: 'Rahul Sharma' });
  assert.equal(res.status, 202);
  const body = await res.json();
  assert.equal(body.ok, true);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].token, TOKEN);
  assert.deepEqual(
    { ...sent[0].data, sentAt: undefined, requestId: undefined },
    {
      type: 'dial_request',
      requestId: undefined,
      phoneNumber: '+919876543210',
      contactName: 'Rahul Sharma',
      source: 'LeadZam CRM',
      sentAt: undefined,
    }
  );
  assert.equal(sent[0].data.requestId, body.requestId);
  for (const v of Object.values(sent[0].data)) assert.equal(typeof v, 'string'); // FCM requires strings
});

test('dial: FCM failure maps to 502 and marks the request failed', async () => {
  await post('/api/register', { token: TOKEN });
  sendImpl = async () => {
    throw new DialError('FCM_SEND_FAILED', 'boom');
  };
  const res = await post('/api/dial', { phoneNumber: '+919876543210' });
  assert.equal(res.status, 502);
  const body = await res.json();
  assert.equal(body.code, 'FCM_SEND_FAILED');
  const rec = await (await fetch(`${base}/api/requests/${body.requestId}`)).json();
  assert.equal(rec.status, 'failed');
});

test('dial: invalid token -> 409 and the stale token is cleared', async () => {
  await post('/api/register', { token: TOKEN });
  sendImpl = async () => {
    throw new DialError('DEVICE_TOKEN_INVALID', 'stale');
  };
  const res = await post('/api/dial', { phoneNumber: '+919876543210' });
  assert.equal(res.status, 409);
  assert.equal((await res.json()).code, 'DEVICE_TOKEN_INVALID');
  const dev = await (await fetch(`${base}/api/device`)).json();
  assert.equal(dev.registered, false);
});

test('dial: Firebase not configured -> 503', async () => {
  await post('/api/register', { token: TOKEN });
  sendImpl = async () => {
    throw new DialError('FCM_NOT_CONFIGURED', 'no creds');
  };
  const res = await post('/api/dial', { phoneNumber: '+919876543210' });
  assert.equal(res.status, 503);
});

test('register: rejects short tokens', async () => {
  const res = await post('/api/register', { token: 'short' });
  assert.equal(res.status, 400);
});

test('ack: advances status, never moves backwards, rejects unknown ids', async () => {
  await post('/api/register', { token: TOKEN });
  const { requestId } = await (await post('/api/dial', { phoneNumber: '+919876543210' })).json();

  let r = await (await post('/api/ack', { requestId, event: 'received' })).json();
  assert.equal(r.status, 'received');
  r = await (await post('/api/ack', { requestId, event: 'opened' })).json();
  assert.equal(r.status, 'opened');
  r = await (await post('/api/ack', { requestId, event: 'received' })).json();
  assert.equal(r.status, 'opened', 'late "received" must not overwrite "opened"');

  assert.equal((await post('/api/ack', { requestId: 'nope', event: 'received' })).status, 404);
  assert.equal((await post('/api/ack', { requestId, event: 'bogus' })).status, 400);
});

test('multiple requests get distinct ids and are all listed', async () => {
  await post('/api/register', { token: TOKEN });
  const a = await (await post('/api/dial', { phoneNumber: '+919876543210' })).json();
  const b = await (await post('/api/dial', { phoneNumber: '+919876543211' })).json();
  assert.notEqual(a.requestId, b.requestId);
  const list = await (await fetch(`${base}/api/requests`)).json();
  assert.equal(list.requests.length, 2);
});
