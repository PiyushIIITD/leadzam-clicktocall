'use strict';

const fs = require('fs');
const path = require('path');

/** Errors with a stable `code` the API layer can map to HTTP statuses. */
class DialError extends Error {
  constructor(code, message, cause) {
    super(message);
    this.code = code;
    this.cause = cause;
  }
}

// FCM error codes that mean "this token will never work again".
const TOKEN_ERRORS = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-argument',
]);

/**
 * Returns an async `send(token, data)` function backed by firebase-admin.
 * Credentials come from GOOGLE_APPLICATION_CREDENTIALS (a file path) and are
 * never read by anything that ships to the browser.
 *
 * Initialisation is lazy so the server (and dashboard) still start when
 * Firebase is not configured yet; /api/dial then reports a clear error.
 */
function createFcmSender({ credentialsPath = process.env.GOOGLE_APPLICATION_CREDENTIALS } = {}) {
  let messaging = null;

  function init() {
    if (messaging) return messaging;
    if (!credentialsPath) {
      throw new DialError(
        'FCM_NOT_CONFIGURED',
        'Firebase is not configured on the server (GOOGLE_APPLICATION_CREDENTIALS is not set).'
      );
    }
    const resolved = path.resolve(credentialsPath);
    if (!fs.existsSync(resolved)) {
      throw new DialError(
        'FCM_NOT_CONFIGURED',
        `Firebase service-account file not found at ${resolved}.`
      );
    }
    // Required lazily so tests never load firebase-admin.
    const admin = require('firebase-admin');
    const serviceAccount = JSON.parse(fs.readFileSync(resolved, 'utf8'));
    const app = admin.apps.length
      ? admin.app()
      : admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    messaging = admin.messaging(app);
    return messaging;
  }

  return {
    isConfigured() {
      try {
        init();
        return true;
      } catch (_) {
        return false;
      }
    },

    async send(token, data) {
      const m = init();
      try {
        // Data-only message: Android always calls onMessageReceived(), in
        // foreground AND background, so the app decides how to present it.
        // A "notification" payload would be shown by the system tray while
        // backgrounded and bypass our code (and the dial action).
        const messageId = await m.send({
          token,
          data,
          android: { priority: 'high', ttl: 120 * 1000 },
        });
        return { messageId };
      } catch (err) {
        if (err instanceof DialError) throw err;
        if (TOKEN_ERRORS.has(err.code)) {
          throw new DialError(
            'DEVICE_TOKEN_INVALID',
            'The registered device token is no longer valid. Open the Android app and register the device again.',
            err
          );
        }
        throw new DialError('FCM_SEND_FAILED', `FCM rejected the request: ${err.message}`, err);
      }
    },
  };
}

module.exports = { createFcmSender, DialError };
