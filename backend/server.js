'use strict';

require('dotenv').config();
const path = require('path');
const { createApp } = require('./src/app');
const { createStore } = require('./src/store');
const { createFcmSender } = require('./src/fcm');

const port = Number(process.env.PORT) || 3000;
const store = createStore({ dataDir: path.join(__dirname, 'data') });
const sender = createFcmSender();
const app = createApp({ sender, store, fallbackToken: process.env.FCM_DEVICE_TOKEN });

// Bind on all interfaces so a physical phone on the same Wi-Fi can reach /api/register and /api/ack.
app.listen(port, '0.0.0.0', () => {
  console.log(`LeadZam Click-to-Call backend on http://localhost:${port}`);
  console.log(`  Firebase configured: ${sender.isConfigured()}`);
  const d = store.getDevice();
  console.log(`  Registered device:   ${d ? d.deviceName : process.env.FCM_DEVICE_TOKEN ? 'via FCM_DEVICE_TOKEN' : 'none yet'}`);
});
