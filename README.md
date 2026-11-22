# Background Recorder

Source preview for Despia V3. The runtime update and automatic discovery rollout are awaiting release. Rebuild and install the native app to include these files; uploading them does not change an installed app.

Records audio in the background and uploads it to your server when recording stops.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

[Extension documentation](https://setup.despia.com/experiments/samples/recorder), [V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `recorder.policy.json`: the build verification policy. It records declarations used by signed build verification. It is not served from your site and does not go into `public/despia/`.

## Requirements

- A Despia V3 app and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- An upload endpoint that accepts an HTTP `PUT` of the recorded file (a presigned storage URL works well).
- iOS 16.6 or later for the Lock Screen widget.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/recorder/` in your web app. The folder name is the extension id, so pages call `extension://recorder/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"recorder"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Rebuild and install the native app with these extension files. Any change to the manifest or native source requires another native build. It cannot be applied over the air.

Signed build verification binds source hashes, dependency pins, app identity and final artifacts. It is separate from human code review and does not require a per-app enrollment switch.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Call the published SDK directly and watch the exact answer variable declared in `manifest.json`. The SDK resolves to an object keyed by that variable; the native envelope is inside it. Check both a missing reply and `ok: false` before using `data`. Native errors resolve through the SDK; they do not reject its promise. A single watched variable becomes `undefined` after 30 seconds without a reply.

Await calls that use the same answer variable sequentially. The SDK's command queue does not correlate concurrent responses. Encode every query value with `encodeURIComponent`, including JSON text. Spaces become `%20`; a literal plus becomes `%2B`. Do not use unmodified `URLSearchParams` form encoding, which uses plus for spaces.

Importing the SDK is safe during server rendering. Run native calls only in browser click handlers or mounted client hooks, including client components in Next.js. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features. Optional event handlers use the declared callback on `globalThis`, not an SDK subscription API. Save and restore any callback your app already owns when a component mounts and unmounts.

## Calling it

```javascript
import despia from 'despia-native';

const answerReply = await despia('extension://recorder/status', ['recorderStatus']);
const answer = answerReply.recorderStatus;
if (answer === undefined) throw new Error('No native reply within 30 seconds');
if (!answer.ok) throw new Error(answer.code + ': ' + answer.error);

console.log(answer.data);
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

Signed build verification checks the exact files included in the native build.

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
import despia from 'despia-native';

// uploadUrl is issued by your server for this recording.
const recordingReply = await despia(`extension://recorder/start?upload=${encodeURIComponent(uploadUrl)}`, ['recorderStart']);
const recording = recordingReply.recorderStart;
if (recording === undefined) throw new Error('No native reply within 30 seconds');
if (!recording.ok) throw new Error(recording.code + ': ' + recording.error);

const id = recording.data.recordingId;
const stoppedReply = await despia(`extension://recorder/stop?id=${encodeURIComponent(id)}`, ['recorderStop']);
const stopped = stoppedReply.recorderStop;
if (stopped === undefined) throw new Error('No native reply within 30 seconds');
if (!stopped.ok) throw new Error(stopped.code + ': ' + stopped.error);

const statusReply = await despia(`extension://recorder/status?id=${encodeURIComponent(id)}`, ['recorderStatus']);
const status = statusReply.recorderStatus;
if (status === undefined) throw new Error('No native reply within 30 seconds');
if (!status.ok) throw new Error(status.code + ': ' + status.error);

console.log(status.data.state);
```

### Recording updates

```javascript
import despia from 'despia-native';

const previousRecorderState = globalThis.onRecorderState;
const handleRecorderState = state => {
  try {
    if (typeof previousRecorderState === 'function') previousRecorderState(state);
  } finally {
    console.log(state.recordingId, state.state, state.durationMs, state.bytes);
  }
};
globalThis.onRecorderState = handleRecorderState;
const cleanupRecorderState = () => {
  if (globalThis.onRecorderState !== handleRecorderState) return;
  if (previousRecorderState === undefined) delete globalThis.onRecorderState;
  else globalThis.onRecorderState = previousRecorderState;
};
// Call cleanupRecorderState() when this component unmounts.


const recordingReply = await despia(`extension://recorder/start?upload=${encodeURIComponent(uploadUrl)}`, ['recorderStart']);
const recording = recordingReply.recorderStart;
if (recording === undefined) throw new Error('No native reply within 30 seconds');
if (!recording.ok) throw new Error(recording.code + ': ' + recording.error);

localStorage.setItem('recordingId', recording.data.recordingId);
// Remove or restore your callback when this page or component unmounts.
```

### Asking for the microphone on Android

On iOS, `start` shows the microphone prompt itself. On Android it does not. Ask for the microphone from the page first with the standard web API. Despia's Android app turns a web microphone request into the system `RECORD_AUDIO` prompt, when the microphone permission is on for your app:

```javascript
import despia from 'despia-native';

const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
stream.getTracks().forEach(track => track.stop());

const recordingReply = await despia(`extension://recorder/start?upload=${encodeURIComponent(uploadUrl)}`, ['recorderStart']);
const recording = recordingReply.recorderStart;
if (recording === undefined) throw new Error('No native reply within 30 seconds');
if (!recording.ok) throw new Error(recording.code + ': ' + recording.error);
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

Set `globalThis.onRecorderState` to receive recording state notifications. Read `status` for current state after re-entering the page.

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

[Despia V3 Extension Source-Available Commercial License](LICENSE), version 1.4.
This template is proprietary and source available.

You may inspect, fork, customize, build and run it for noncommercial development,
evaluation and testing without a paid Despia license, including work on an app
you plan to release commercially later.

Production or commercial use requires a valid Despia project and an applicable
Despia license for that application and operating system. A Solo Pack covers
its licensed OS, iOS or Android. A Hybrid Pack covers both. Your applicable
license includes all provided V3 templates you have lawful access to and any
number of your own extensions, with no separate extension purchase or
per-extension fee.

Keep the license and applicable notices. Third-party material keeps its own
terms. Experimental V3 extensions do not include a commitment to maintenance
or a V4 port. See [LICENSE](LICENSE) for the full terms and [Despia](https://despia.com)
for licensing.
