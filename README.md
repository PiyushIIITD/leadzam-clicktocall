# LeadZam Click-to-Call

Click **Call** on a lead in a browser CRM dashboard → a small backend sends an FCM message → a Kotlin Android app receives it → the user taps **Call** on the phone → the **native dialer opens with the number pre-filled**. (The call is never placed automatically.)

```
CRM Web UI ──POST /api/dial──▶ Backend (Node/Express + firebase-admin)
                                   │  FCM data message (high priority)
                                   ▼
                          Firebase Cloud Messaging
                                   │
                                   ▼
                       Android app (Kotlin)
                        ├─ app open       → in-app card  [Call] [Dismiss]
                        └─ app background → notification [Call] [Dismiss]
                                   │ user taps Call
                                   ▼
                       ACTION_DIAL tel:+91… (native dialer)

        Android ──POST /api/ack (received | opened | dismissed)──▶ Backend ──▶ Dashboard
```

## Repository layout

| Path | What |
|---|---|
| `backend/` | Express API (`/api/dial`, `/api/register`, `/api/ack`, …) and the static CRM dashboard in `backend/public/` |
| `android/` | Kotlin Android app (`com.leadzam.clicktocall`) |

## Prerequisites

- Node.js 18+
- Android Studio (or JDK 17+ and the Android SDK), an emulator **with Google Play services** (e.g. a "Google Play" system image) or a physical Android phone
- A Firebase project (free Spark plan is enough)

## 1. Firebase setup (one-time)

1. Go to <https://console.firebase.google.com> → **Add project**.
2. **Add an Android app** to it with package name **`com.leadzam.clicktocall`**.
3. Download **`google-services.json`** and put it at **`android/app/google-services.json`**.
4. **Project settings → Service accounts → Generate new private key.** Save the JSON as **`backend/secrets/service-account.json`**.

Both files are git-ignored (`.gitignore`). **Never commit them.** Cloud Messaging uses the FCM HTTP v1 API, which is enabled by default on new projects.

## 2. Run the backend + dashboard

```bash
cd backend
cp .env.example .env          # defaults point to ./secrets/service-account.json
npm install
npm start                     # http://localhost:3000
```

Open <http://localhost:3000> for the CRM dashboard. The badge in the top-right tells you whether Firebase credentials are loaded and whether a phone is registered.

## 3. Run the Android app

```bash
cd android
./gradlew installDebug        # or open android/ in Android Studio and press Run
```

On the phone/emulator:

1. Grant the notification permission when asked (Android 13+).
2. Set the **Backend URL**:
   - Emulator: leave the default `http://10.0.2.2:3000` (10.0.2.2 is the emulator's alias for your computer).
   - Physical phone: use your computer's LAN IP, e.g. `http://192.168.1.10:3000` (same Wi-Fi).
3. Tap **Register with backend**. The dashboard badge turns green: `Phone: <model> (<token preview>)`.
   - Alternative: tap **Copy token** and set `FCM_DEVICE_TOKEN=` in `backend/.env`.

## 4. Try it

1. In the dashboard, click **Call** next to a lead. State goes *Ready → Sending… → Request sent (accepted by FCM) → Received by phone*.
2. **App open:** an in-app card shows the name, number and "Request from LeadZam CRM". Tap **Call**.
3. **App in background / closed:** a heads-up notification appears. Tap **Call** in the notification.
4. The native dialer opens with the number filled in. The dashboard shows *Dialer opened on phone*.

## Design decisions (what to explain in the review)

**Data-only FCM messages.** The backend sends only a `data` payload, no `notification` block. For data messages Android *always* calls `FirebaseMessagingService.onMessageReceived`, in foreground and background, so the app controls presentation. With a `notification` block a backgrounded app gets a system-tray notification handled by the SDK, whose tap only opens the launcher and never runs our dial code. The message uses `priority: high` and `ttl: 120s` so a stale click-to-call doesn't ring minutes later.

```json
{ "type": "dial_request", "requestId": "<uuid>", "phoneNumber": "+919876543210",
  "contactName": "Rahul Sharma", "source": "LeadZam CRM", "sentAt": "1760000000000" }
```

**Foreground vs background** (`IncomingRequestHandler`, `AppState`):
- *Foreground* (MainActivity started): no notification; the list updates live with a card. We never `startActivity` over what the user is doing.
- *Background / killed*: a notification on a high-importance channel with **Call** and **Dismiss** actions. Tapping the body opens the app to the card. Why a notification: it is the Android-sanctioned way to get attention from the background; Android 10+ also blocks background activity launches, so force-opening the dialer is not an option.
- The notification's **Call** goes through `DialActivity`, an invisible trampoline *activity*. Android 12+ forbids starting activities from broadcast receivers/services in response to notification taps, but allows an activity trampoline.

**`ACTION_DIAL`, not `ACTION_CALL`.** Needs no `CALL_PHONE` permission, can't place a call by itself, and is exactly "open the dialer with the number".

**Honest delivery status.** FCM's success only means "accepted". The dashboard shows *Request sent (accepted by FCM)* until the Android app POSTs `/api/ack {event: received}` from `onMessageReceived`; only then *Received by phone*. Later events: `opened`, `dismissed`. The backend never moves a status backwards.

**Secrets.** The browser only talks to `/api/*`. The service-account key is read server-side from a git-ignored path. `google-services.json` identifies the Firebase project (not a server secret), but is also git-ignored.

### Edge cases handled

| Case | Behaviour |
|---|---|
| Empty / invalid number | Backend `400 INVALID_PHONE` (7–15 digits, optional leading `+`); the dashboard shows the error. The phone re-validates payloads before building a `tel:` URI. Demo lead "Invalid Number Demo" shows this. |
| No device registered | `409 NO_DEVICE` with instructions |
| Stale/invalid FCM token | `409 DEVICE_TOKEN_INVALID`; stored token is cleared; app re-registers on next token refresh (`onNewToken`) or via the button |
| Firebase credentials missing / FCM failure | `503 FCM_NOT_CONFIGURED` / `502 FCM_SEND_FAILED` shown in the UI; the request is recorded as failed |
| App open vs background | See above |
| User ignores | Notification auto-expires after 10 min; card stays in the list as pending |
| User dismisses | Dismiss button or swipe-away → marked *Dismissed* and acked |
| Several requests | Each has its own `requestId` and notification id → they stack instead of replacing each other; list shows all (last 20) |
| FCM redelivers the same message | De-duplicated by `requestId` on the phone |
| Notification permission denied | Request is still stored and shown in-app; user is told once |
| No dialer app on device | Toast instead of crash |
| Ack fails (backend unreachable) | Best effort; never blocks dialing |

## API

| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/dial` | `{phoneNumber, contactName?}` | `202 {ok, requestId, messageId}` or `4xx/5xx {ok:false, code, error}` |
| `POST /api/register` | `{token, deviceName?}` | stores the single demo device (persisted in `backend/data/`, git-ignored) |
| `POST /api/ack` | `{requestId, event: received\|opened\|dismissed}` | called by the Android app |
| `GET /api/requests`, `GET /api/requests/:id` | | history / status polling |
| `GET /api/device` | | registered device + whether Firebase is configured |

## Tests

```bash
cd backend && npm test                      # 11 API tests (validation, FCM failure mapping, ack ordering, …)
cd android && ./gradlew testDebugUnitTest   # payload parser tests
```

**Manual verification performed** (Pixel 8 emulator, API 36, real FCM through a Firebase project: dashboard/API call → FCM → phone → ack `received` → notification **Call** → dialer → ack `opened`; plus simulated cases below): app foreground → in-app card and zero notifications; app backgrounded → two stacked notifications; duplicate `requestId` ignored; invalid number ignored; tapping notification **Call** launches `ACTION_DIAL tel:` into the system dialer. A debug-only broadcast receiver (`android/app/src/debug/`, not in release builds) feeds the same handler the FCM service uses, so these paths can be exercised without Firebase:

```bash
adb shell "am broadcast -n com.leadzam.clicktocall/.DebugSimulateReceiver \
  --es phoneNumber +919876543210 --es contactName 'Rahul Sharma'"
```

## Assumptions, shortcuts, and unfinished items

- One demo device; the token is stored in a JSON file. No auth on `/api/*` (login is out of scope). Anyone who can reach the backend can trigger a dial request — production needs auth and per-user device lookup.
- `usesCleartextTraffic=true` so the phone can talk to `http://<LAN IP>:3000`. Production: HTTPS only.
- The ack is a plain background-thread HTTP call inside the FCM service; production should use WorkManager for retries.
- Lead data is mocked in `backend/public/app.js`.

## Screen Recording Demo
https://drive.google.com/file/d/15DJKg3UbNOmlu6vmsTPQX3sx46J0uy0q/view?usp=sharing

## Production notes

**Multiple devices:** key devices by user (`userId → [deviceId, token, name, lastSeen]`), let the CRM pick the target device (or "last active"), send to one token per request, prune tokens that return `UNREGISTERED`.

**Dual SIM:** `ACTION_DIAL` can't choose a SIM. In production I'd add an optional `simSlot` to the payload, request `READ_PHONE_STATE`, map slot → `PhoneAccountHandle` via `TelecomManager.getCallCapablePhoneAccounts()`, and pass `TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE` on the dial intent (with `CALL_PHONE` if auto-placing), falling back to the system SIM chooser.

## AI Usage Notes

- **Tools used:** an AI coding assistant for scaffolding the backend, dashboard and Android app, writing tests and this README.
- **Where AI helped most:** boilerplate (Gradle/Manifest/RecyclerView plumbing), enumerating edge cases, and generating the adb-based emulator test harness.
- **A suggestion I changed / found wrong:**
  - After building, the emulator screenshot showed the UI drawn *under* the status bar (targetSdk 35 enforces edge-to-edge) → added `fitsSystemWindows`.
  - Android auto-added its own "Call number" smart action to the notification next to ours (confusing, and bypasses our "opened" ack) → disabled with `setAllowSystemGeneratedContextualActions(false)`.
  - Initial plan considered a `notification` payload because it is simpler; I rejected it because the background tap would bypass `onMessageReceived` and the dial/ack logic.
- **With two more hours:** WorkManager-based ack retries, a request-expiry check on the phone, token-based auth for the API, instrumented Android tests for the notification actions, and multi-device selection in the dashboard.
# leadzam-clicktocall
