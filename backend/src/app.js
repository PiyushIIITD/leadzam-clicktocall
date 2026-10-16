'use strict';

const crypto = require('crypto');
const path = require('path');
const express = require('express');
const { normalizePhone, normalizeName } = require('./validate');
const { DialError } = require('./fcm');

const STATUS_BY_CODE = {
  NO_DEVICE: 409,
  DEVICE_TOKEN_INVALID: 409,
  FCM_NOT_CONFIGURED: 503,
  FCM_SEND_FAILED: 502,
};

const ACK_EVENTS = new Set(['received', 'opened', 'dismissed']);

/**
 * Builds the Express app. `sender` and `store` are injected so the HTTP layer
 * can be tested without Firebase.
 *
 * Request lifecycle (request.status):
 *   sent        FCM accepted the message ("accepted for sending", NOT delivered)
 *   received    Android app acked that onMessageReceived ran
 *   opened      user tapped Call and the native dialer was opened
 *   dismissed   user dismissed the request
 */
function createApp({ sender, store, fallbackToken }) {
  const app = express();
  app.use(express.json({ limit: '10kb' }));
  app.use(express.static(path.join(__dirname, '..', 'public')));

  const targetToken = () => (store.getDevice() && store.getDevice().token) || fallbackToken || null;

  // --- Device registration -------------------------------------------------

  app.post('/api/register', (req, res) => {
    const { token, deviceName } = req.body || {};
    if (typeof token !== 'string' || token.length < 20 || token.length > 4096) {
      return res.status(400).json({ ok: false, code: 'INVALID_TOKEN', error: 'A valid FCM token is required.' });
    }
    const name = typeof deviceName === 'string' ? deviceName.trim().slice(0, 60) : '';
    const device = store.setDevice({ token, deviceName: name });
    res.json({ ok: true, deviceName: device.deviceName, registeredAt: device.registeredAt });
  });

  app.get('/api/device', (_req, res) => {
    const device = store.getDevice();
    const token = targetToken();
    res.json({
      registered: Boolean(token),
      deviceName: device ? device.deviceName : token ? 'Configured via env' : null,
      registeredAt: device ? device.registeredAt : null,
      tokenPreview: token ? `${token.slice(0, 8)}…${token.slice(-6)}` : null,
      fcmConfigured: sender.isConfigured(),
    });
  });

  // --- Click to call -------------------------------------------------------

  app.post('/api/dial', async (req, res) => {
    const body = req.body || {};
    const phoneNumber = normalizePhone(body.phoneNumber);
    if (!phoneNumber) {
      return res.status(400).json({
        ok: false,
        code: 'INVALID_PHONE',
        error: 'phoneNumber is required and must contain 7-15 digits (optional leading +).',
      });
    }
    const contactName = normalizeName(body.contactName);
    if (contactName === null) {
      return res.status(400).json({
        ok: false,
        code: 'INVALID_NAME',
        error: 'contactName must be a string of at most 100 characters.',
      });
    }

    const token = targetToken();
    if (!token) {
      return res.status(STATUS_BY_CODE.NO_DEVICE).json({
        ok: false,
        code: 'NO_DEVICE',
        error: 'No Android device is registered. Open the app and tap "Register with backend".',
      });
    }

    const requestId = crypto.randomUUID();
    const record = {
      requestId,
      phoneNumber,
      contactName,
      status: 'sending',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      error: null,
    };
    store.addRequest(record);

    try {
      // All FCM data values must be strings.
      const { messageId } = await sender.send(token, {
        type: 'dial_request',
        requestId,
        phoneNumber,
        contactName,
        source: 'LeadZam CRM',
        sentAt: String(Date.now()),
      });
      record.status = 'sent';
      record.messageId = messageId;
      record.updatedAt = new Date().toISOString();
      res.status(202).json({ ok: true, requestId, status: record.status, messageId });
    } catch (err) {
      const code = err instanceof DialError ? err.code : 'INTERNAL_ERROR';
      record.status = 'failed';
      record.error = { code, message: err.message };
      record.updatedAt = new Date().toISOString();
      if (code === 'DEVICE_TOKEN_INVALID' && store.getDevice()) store.clearDevice();
      if (code === 'INTERNAL_ERROR') console.error('[dial] unexpected error', err);
      res.status(STATUS_BY_CODE[code] || 500).json({
        ok: false,
        code,
        requestId,
        error: code === 'INTERNAL_ERROR' ? 'Unexpected server error.' : err.message,
      });
    }
  });

  // --- Acknowledgement from the Android app --------------------------------

  app.post('/api/ack', (req, res) => {
    const { requestId, event } = req.body || {};
    if (typeof requestId !== 'string' || !ACK_EVENTS.has(event)) {
      return res.status(400).json({ ok: false, code: 'INVALID_ACK', error: 'requestId and a valid event are required.' });
    }
    const record = store.getRequest(requestId);
    if (!record) return res.status(404).json({ ok: false, code: 'UNKNOWN_REQUEST', error: 'Unknown requestId.' });
    // Never move a request backwards (e.g. "received" arriving after "opened").
    const rank = { sending: 0, sent: 1, received: 2, dismissed: 3, opened: 3 };
    if (rank[event] >= rank[record.status]) {
      record.status = event;
      record.updatedAt = new Date().toISOString();
    }
    res.json({ ok: true, status: record.status });
  });

  // --- History / status polling for the dashboard -------------------------

  app.get('/api/requests', (_req, res) => res.json({ requests: store.listRequests() }));

  app.get('/api/requests/:id', (req, res) => {
    const record = store.getRequest(req.params.id);
    if (!record) return res.status(404).json({ ok: false, code: 'UNKNOWN_REQUEST', error: 'Unknown requestId.' });
    res.json(record);
  });

  // --- Fallbacks -----------------------------------------------------------

  // Malformed JSON etc.
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err.type === 'entity.parse.failed' || err.status === 400) {
      return res.status(400).json({ ok: false, code: 'BAD_REQUEST', error: 'Request body must be valid JSON.' });
    }
    console.error(err);
    res.status(500).json({ ok: false, code: 'INTERNAL_ERROR', error: 'Unexpected server error.' });
  });

  return app;
}

module.exports = { createApp };
