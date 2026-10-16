'use strict';

// Mock CRM data. A real CRM would load these from its own API.
const LEADS = [
  { id: 1, name: 'Rahul Sharma', phone: '+91 98765 43210', stage: 'Interested' },
  { id: 2, name: 'Priya Verma', phone: '+91 91234 56789', stage: 'New' },
  { id: 3, name: 'Amit Patel', phone: '+91 99887 76655', stage: 'Follow-up' },
  { id: 4, name: 'Invalid Number Demo', phone: '12-ab', stage: 'New' },
];

const STATE_LABEL = {
  ready: 'Ready',
  sending: 'Sending…',
  sent: 'Request sent (accepted by FCM)',
  received: 'Received by phone',
  opened: 'Dialer opened on phone',
  dismissed: 'Dismissed on phone',
  failed: 'Error',
  error: 'Error',
};
const TERMINAL = new Set(['opened', 'dismissed', 'failed', 'error']);
const POLL_MS = 1500;
const POLL_MAX_MS = 5 * 60 * 1000;

const $ = (sel) => document.querySelector(sel);
const leadsEl = $('#leads');
const historyEl = $('#history');
const deviceEl = $('#device');

// leadId -> { state, detail }
const leadState = new Map(LEADS.map((l) => [l.id, { state: 'ready', detail: '' }]));

function setLeadState(leadId, state, detail = '') {
  leadState.set(leadId, { state, detail });
  renderLeads();
}

function renderLeads() {
  leadsEl.replaceChildren(
    ...LEADS.map((lead) => {
      const { state, detail } = leadState.get(lead.id);
      const busy = state === 'sending';
      const li = document.createElement('li');
      if (state !== 'ready') li.classList.add('active');

      const info = document.createElement('div');
      info.className = 'lead-info';
      info.innerHTML = '<div class="lead-name"></div><div class="lead-phone"></div><span class="stage"></span>';
      info.querySelector('.lead-name').textContent = lead.name;
      info.querySelector('.lead-phone').textContent = lead.phone;
      const stage = info.querySelector('.stage');
      stage.textContent = `Stage: ${lead.stage}`;
      stage.classList.add(lead.stage);

      const st = document.createElement('div');
      st.className = `state ${state}`;
      st.setAttribute('aria-live', 'polite');
      st.textContent = detail ? `${STATE_LABEL[state]} – ${detail}` : STATE_LABEL[state];

      const btn = document.createElement('button');
      btn.className = 'call';
      btn.type = 'button';
      btn.disabled = busy;
      btn.textContent = busy ? 'Sending…' : 'Call';
      btn.setAttribute('aria-label', `Call ${lead.name} on ${lead.phone}`);
      btn.addEventListener('click', () => callLead(lead));

      li.append(info, st, btn);
      return li;
    })
  );
}

async function callLead(lead) {
  setLeadState(lead.id, 'sending');
  try {
    const res = await fetch('/api/dial', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phoneNumber: lead.phone, contactName: lead.name }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok || !body.ok) {
      setLeadState(lead.id, 'error', body.error || `Server returned ${res.status}`);
    } else {
      setLeadState(lead.id, 'sent');
      pollRequest(lead.id, body.requestId);
    }
  } catch (_) {
    setLeadState(lead.id, 'error', 'Could not reach the backend. Is it running?');
  }
  refreshHistory();
}

// Poll until the phone acks, so the dashboard only claims "received" when true.
async function pollRequest(leadId, requestId) {
  const started = Date.now();
  while (Date.now() - started < POLL_MAX_MS) {
    await new Promise((r) => setTimeout(r, POLL_MS));
    try {
      const rec = await (await fetch(`/api/requests/${requestId}`)).json();
      const current = leadState.get(leadId);
      // A newer click on the same lead supersedes this poll.
      if (current.state === 'sending') return;
      if (rec.status && rec.status !== current.state) setLeadState(leadId, rec.status);
      if (TERMINAL.has(rec.status)) break;
    } catch (_) {
      /* transient; keep polling */
    }
  }
  refreshHistory();
}

async function refreshDevice() {
  try {
    const d = await (await fetch('/api/device')).json();
    if (!d.fcmConfigured) {
      deviceEl.className = 'device bad';
      deviceEl.textContent = 'Backend: Firebase credentials missing';
    } else if (!d.registered) {
      deviceEl.className = 'device bad';
      deviceEl.textContent = 'No phone registered – open the Android app and tap Register';
    } else {
      deviceEl.className = 'device ok';
      deviceEl.textContent = `Phone: ${d.deviceName} (${d.tokenPreview})`;
    }
  } catch (_) {
    deviceEl.className = 'device bad';
    deviceEl.textContent = 'Backend unreachable';
  }
}

async function refreshHistory() {
  try {
    const { requests } = await (await fetch('/api/requests')).json();
    if (!requests.length) {
      historyEl.innerHTML = '<li class="empty">No requests yet.</li>';
      return;
    }
    historyEl.replaceChildren(
      ...requests.slice(0, 10).map((r) => {
        const li = document.createElement('li');
        const main = document.createElement('div');
        main.className = 'lead-info';
        main.textContent = `${r.contactName || 'Unknown'} · ${r.phoneNumber}`;
        const when = document.createElement('div');
        when.className = 'when';
        when.textContent = new Date(r.createdAt).toLocaleTimeString();
        main.append(when);
        const st = document.createElement('div');
        st.className = `state ${r.status}`;
        st.textContent = STATE_LABEL[r.status] || r.status;
        li.append(main, st);
        return li;
      })
    );
  } catch (_) {
    /* ignore */
  }
}

renderLeads();
refreshDevice();
refreshHistory();
setInterval(refreshDevice, 5000);
setInterval(refreshHistory, 3000);
