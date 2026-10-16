'use strict';

const fs = require('fs');
const path = require('path');

const MAX_REQUESTS = 50;

/**
 * Tiny persistence layer. Deliberately not a database (out of scope):
 *  - the registered device token lives in a small JSON file so it survives a
 *    backend restart (the Android token rarely changes);
 *  - call-request history is in memory only.
 */
function createStore({ dataDir } = {}) {
  const deviceFile = dataDir ? path.join(dataDir, 'device.json') : null;
  let device = null;
  const requests = new Map(); // requestId -> request, insertion ordered

  if (deviceFile) {
    try {
      device = JSON.parse(fs.readFileSync(deviceFile, 'utf8'));
    } catch (_) {
      device = null;
    }
  }

  return {
    getDevice() {
      return device;
    },

    setDevice({ token, deviceName }) {
      device = {
        token,
        deviceName: deviceName || 'Android device',
        registeredAt: new Date().toISOString(),
      };
      if (deviceFile) {
        fs.mkdirSync(path.dirname(deviceFile), { recursive: true });
        fs.writeFileSync(deviceFile, JSON.stringify(device), { mode: 0o600 });
      }
      return device;
    },

    clearDevice() {
      device = null;
      if (deviceFile) {
        try {
          fs.unlinkSync(deviceFile);
        } catch (_) {
          /* already gone */
        }
      }
    },

    addRequest(req) {
      requests.set(req.requestId, req);
      while (requests.size > MAX_REQUESTS) {
        requests.delete(requests.keys().next().value);
      }
      return req;
    },

    getRequest(id) {
      return requests.get(id) || null;
    },

    listRequests() {
      return Array.from(requests.values()).reverse();
    },
  };
}

module.exports = { createStore };
