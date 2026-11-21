# Background Recorder

Source preview for Despia V3. This snapshot requires the runtime update currently awaiting release and an approved extension-enabled native build. Uploading these files does not change an installed app.

Records audio in the background and uploads it to your server when recording stops.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

[Extension documentation](https://setup.despia.com/experiments/samples/recorder), [V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `recorder.policy.json`: the approval policy. It is a file you send to Despia with your approval request, together with the extension files. It is not served from your site and does not go into `public/despia/`.

## Requirements

- A Despia V3 app with V3 Extensions enabled, and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- An upload endpoint that accepts an HTTP `PUT` of the recorded file (a presigned storage URL works well).
- iOS 16.6 or later for the Lock Screen widget.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/recorder/` in your web app. The folder name is the extension id, so pages call `extension://recorder/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"recorder"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Request approval and a new native build in Despia, and send `recorder.policy.json` with the request. Despia reviews and approves these exact files before a build includes them, and any change to the manifest or to a native file needs a new approval and a new native build. It cannot be applied over the air.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Copy `example/src/lib/despia-extension.js` to your app's `src/lib/` and adjust the import path if needed. It imports `despia` from `despia-native`, calls the SDK and returns the native answer's `data`. It checks native error envelopes and throws an error with their `code`. The SDK itself resolves native `ok: false` envelopes; it does not reject them. A single watched value becomes `undefined` after 30 seconds, which this helper reports as `sdk_timeout`.

Each helper call forwards a unique native callback into a separate watched value, so overlapping calls do not share response globals. `onExtensionEvent` registers the declared native event callbacks and returns a cleanup function. Events are callbacks, not values you can poll from the SDK proxy.

Importing the SDK is safe during server rendering. Run calls and event subscriptions only from browser click handlers or mounted client hooks. React and Vue can use the browser APIs used by this helper; in Next.js and other server-rendered apps, keep these calls in client code. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features.

## Calling it

```javascript
import { extensionCall } from './lib/despia-extension.js';

const answer = await extensionCall('recorder', 'status');
console.log(answer);
```

Calls use `extension://recorder/<route>?<params>`. Each route answers once, in the page that made the call: the runtime sets the route's answer variable in the page, then calls the route's callback. Only declared parameters are accepted. Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters.

Every answer has the same shape: `ok`, `provider`, `platform`, `runtime` (`3`), `bridge` (`1`), `extension`, `route`, `data` (your handler's data on success, `null` on error), and on error `error` (a message) and `code`.

Standard error codes: `bad_request` (an undeclared or missing parameter, or a value of the wrong type), `not_registered` (this build does not contain the extension or route), `forbidden_frame` (the call did not come from the top-level page), `forbidden_origin` (an origin that is not allowed), `rate_limited` (more than 20 calls per second to one extension), `timeout` (no answer within the route's `timeout_ms`), `unavailable`, `platform_limit` (this platform or OS version has no such capability), `handler_error`, plus the codes this extension lists below.

---

## About

This extension records audio from the microphone, keeps recording when the app goes to the background, and uploads the file to your server when recording stops. It shows its state in a Lock Screen widget on iOS, and in a foreground service notification and a home screen widget on Android.

The page only ever sees a recording id and its state. The audio never crosses the bridge.

---

## Values to set before you build

Before you build, change these values to your own:

- the upload host `upload.example.com` in both `hosts` lists in `manifest.json`.

Despia reviews and approves these exact files before a build includes them.

---

## Permissions and capabilities

The manifest declares everything the recorder needs. The build adds them to your app.

### iOS

- **Microphone.** `NSMicrophoneUsageDescription`, declared under `requires.usage_descriptions`. Set the purpose text in Despia.
- **Background modes.** `audio` (keep recording in the background) and `processing` (finish uploads in a background processing task named `upload`).
- **App Group.** The logical group `nativeext`, shared by the app and the `RecorderStatusWidget` target. It becomes `group.<bundle id>.nx.nativeext`. Register it in the Apple Developer portal and add it to both bundle IDs, as for the Home Screen Widgets extension.
- **Widget.** `RecorderStatusWidget`, a WidgetKit target with a deployment target of iOS 16.6.

### Android

- **Microphone.** `android.permission.RECORD_AUDIO`, declared under `requires.permissions`. Turn on the microphone permission for your app in Despia. The recorder does not ask for it on Android: it must be granted before `start`, otherwise `start` answers `permission_denied`. See [Asking for the microphone on Android](#asking-for-the-microphone-on-android).
- **Foreground service.** `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE`, with the service type `microphone`. Google Play asks you to declare this use.
- **Notifications.** `POST_NOTIFICATIONS`, for the recording notification and its stop action.
- **Widget.** `StatusWidgetProvider`, an exported app widget receiver.
- **Libraries.** `androidx.core:core:1.18.0` and `androidx.work:work-runtime:2.11.2`.

---

## Using it from your web app

The upload URL is the address the file is sent to with an HTTP `PUT`, with `Content-Type: audio/mp4` and an `X-Recording-Id` header. A presigned storage URL works well. Its host must be in the route's `hosts` list.

### Recording lifecycle

```javascript
import { extensionCall } from './lib/despia-extension.js';

// uploadUrl is issued by your server for this recording.
const recording = await extensionCall('recorder', 'start', { upload: uploadUrl });
const id = recording.recordingId;
await extensionCall('recorder', 'stop', { id });
const state = await extensionCall('recorder', 'status', { id });
console.log(state.state);
```

### Recording updates

```javascript
import { extensionCall, onExtensionEvent } from './lib/despia-extension.js';

const stopListening = onExtensionEvent('onRecorderState', state => {
  console.log(state.recordingId, state.state, state.durationMs, state.bytes);
});
const recording = await extensionCall('recorder', 'start', { upload: uploadUrl });
localStorage.setItem('recordingId', recording.recordingId);
// Call stopListening() when this page or component unmounts.
```

### Asking for the microphone on Android

On iOS, `start` shows the microphone prompt itself. On Android it does not. Ask for the microphone from the page first with the standard web API. Despia's Android app turns a web microphone request into the system `RECORD_AUDIO` prompt, when the microphone permission is on for your app:

```javascript
async function ensureMicrophone() {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  stream.getTracks().forEach(track => track.stop());
}

await ensureMicrophone();
await extensionCall('recorder', 'start', { upload: uploadUrl });
```

If the user denies it, `getUserMedia` throws and `start` answers `permission_denied`.

### Routes

| Route     | Parameters                 | Answer variable and callback          | What it does                                                                                       |
| --------- | -------------------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `start`   | `upload` (required)        | `recorderStart`, `onRecorderStart`    | Starts a recording and answers with its `recordingId`                                               |
| `stop`    | `id` (required)            | `recorderStop`, `onRecorderStop`      | Stops the recording and starts the upload                                                           |
| `status`  | `id` (optional)            | `recorderStatus`, `onRecorderStatus`  | The state of a recording, or of the latest one without `id`; `idle` when there is none              |
| `discard` | `id` (required)            | `recorderDiscard`, `onRecorderDiscard`| Deletes a stopped recording and its file                                                            |
| `upload`  | `id`, `upload` (required)  | `recorderUpload`, `onRecorderUpload`  | Sends a saved recording again to a fresh URL, for example after a presigned URL expired             |

Subscribe to `onRecorderState` with `onExtensionEvent`; its payload contains each recording state change.

### States

| State         | Meaning                                                                                   |
| ------------- | ----------------------------------------------------------------------------------------- |
| `starting`    | The recording is being set up                                                             |
| `recording`   | Recording                                                                                 |
| `interrupted` | Paused by the system                                                                      |
| `uploading`   | Stopped, and the file is being uploaded                                                   |
| `uploaded`    | The upload finished                                                                       |
| `failed`      | The upload failed. `reason` says why. Call `upload` with a fresh URL to try again          |
| `terminated`  | The app was closed while recording. The file was never finished and is never uploaded      |
| `discarding`  | The file is being deleted                                                                 |
| `discarded`   | The recording is gone                                                                     |

Answers and events carry `recordingId`, `state`, `durationMs` and `bytes`, plus `httpStatus` after an upload attempt and `reason` when the state is `failed`. While the recorder could not save its index, they also carry `durable: false` and `storage`.

### Error codes

Besides the standard codes, the recorder answers:

| Code                  | When                                                                                       |
| --------------------- | ------------------------------------------------------------------------------------------ |
| `busy`                | A recording is already running, or the recording is not in a state that allows this route  |
| `permission_denied`   | Microphone access is not granted                                                           |
| `not_found`           | No recording has this id                                                                   |
| `record_failed`       | The recorder could not start, or the recording was never finished, so there is nothing to upload |
| `storage_unavailable` | The recorder's index cannot be read yet. Try again later                                   |
| `storage_failed`      | The index could not be saved, or the upload could not be scheduled. Nothing changed        |
| `index_recovered`     | The index was unreadable and was replaced, so an older recording may be missing            |
| `discard_failed`      | The file could not be deleted yet. It stays `discarding` and is deleted on the next `discard` or launch |

---

## Platform limits

- **iOS.** Recording must start while the app is in the foreground. It continues in the background while the audio session stays active. A force-quit ends it. Uploads run while the app runs, in the limited background time iOS may give, and in a background processing task if iOS grants one. iOS guarantees neither. An upload that is cut off starts again from the beginning the next time the app runs.
- **Android.** Android 14 and later only start a microphone foreground service while the app is visible, so call `start` from a page the user is looking at. Lock Screen widgets depend on the device; the foreground service notification is always shown. Uploads run through WorkManager.

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE).
You may inspect the source. Using or modifying this extension requires a valid
Despia commercial license and a valid Despia project for the particular app.
The standard price is **USD 249 per application per operating system**.
iOS and Android require separate licenses. See [Despia](https://despia.com).
Third-party SDKs keep their own licenses. Experimental V3 extensions do not
include a commitment to maintenance or a V4 port.

## Source and licensing

Browse, clone or fork this source to inspect it. Public GitHub forks remain public. For private development, clone into a private workspace or private repository. Modification, building, execution and deployment require a valid Despia commercial license and a valid Despia project for the application. The standard price is USD 249 per application per operating system. Licensed users may customize the extension or use it as a reference for new functionality in that licensed app. Keep the license and applicable notices. See [LICENSE](LICENSE) for the terms.
