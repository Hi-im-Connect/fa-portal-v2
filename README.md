<p align="center">
  <img src="./static/fastautomate.png" width="120" alt="FastAutomate">
</p>

<h1 align="center">FastAutomate v2</h1>

<p align="center">
  The Android app of <b>FastAutomate Mobile RPA</b>: an AI agent that lives on the phone and does the work for you.<br>
  Chat with it from a floating bubble in any app, or send it tasks from the
  <a href="https://github.com/Hi-im-Connect/mobile-rpa-v2">Mobile RPA dashboard</a>.
</p>

---

## What it does

- **Runs the agent on the phone.** A planner model breaks the task into goals, an executor model reads the
  screen (accessibility tree plus screenshot) and taps, types, scrolls and opens apps, one action at a time.
- **Chat bubble, Messenger style.** A round bubble floats over every app. Tap it and a chat grows out of it:
  ask anything, or ask it to do something on the phone. Several chats, switch between them, "+" opens the
  chat list, drag a chat head onto the X to delete it.
- **A chat layer on top of the agent.** The assistant you talk to decides when a task is needed, hands one
  clear instruction to the planner, then checks the result (and the final screenshot) against what you asked.
  If something is wrong or missing it says so and runs a corrected task, up to 3 attempts.
- **Stop and pause any time.** While a task runs the bubble turns red: tap to stop, the small button under it
  pauses. Long-press the red bubble to open the chat mid-task. Pause and stop also work from the dashboard.
- **Zero-tap setup.** The invite download is stamped with a one-time token, so the app joins the dashboard on
  first launch. The app then walks through each permission by itself.
- **Reports everything.** Every run, step and chat line is sent to the dashboard and kept until acknowledged,
  so nothing is lost when the phone is offline.

## How it fits together

```
 you ── chat bubble ──> ChatBrain ──run_task──> Planner ──goals──> Executor ──> taps, typing, apps
                           ^                                            │
                           └──────── result + final screenshot ─────────┘   (checked, retried if wrong)

 phone <──── WebSocket (agent/run, agent/pause, agent/stop / agent/event, agent/finished, agent/chat) ────> dashboard
```

| Part | Where |
|---|---|
| Agent loop (plan, observe, act, pause, stop) | `app/src/main/java/com/mobilerun/portal/agent/AgentLoop.kt` |
| Chat layer and result checking | `agent/ChatBrain.kt`, `agent/ChatFollowUp.kt`, `agent/Chats.kt` |
| Bubble and chat overlay | `ui/home/FaBubble.kt`, `ui/home/FaChat.kt` |
| Home, Chats, Tasks and Settings screens | `ui/home/HomeActivity.kt` |
| Link to the dashboard | `agent/AgentHost.kt`, `service/ReverseConnectionService.kt` |

## Install

Get the APK from your dashboard's invite link. It connects by itself on first launch.

The app then asks for, one after another:

1. **Notifications**, to show when a task is running.
2. **Battery**, so Android does not stop it in the background.
3. **Accessibility**, so the agent can read and control the screen. On Android 13 and newer, sideloaded apps
   need one extra step: App info, then the three-dot menu, then *Allow restricted settings*.
4. **Display over other apps**, so the chat opens over any app without covering the gesture bar.

## Build

Requirements: JDK 17, the Android SDK (build-tools 35), and a signing key.

```bash
# signing/keystore.env (kept out of git)
DROIDRUN_KEYSTORE_PATH=...
DROIDRUN_KEYSTORE_PASSWORD=...
DROIDRUN_KEYSTORE_KEY_ALIAS=...
DROIDRUN_KEYSTORE_KEY_PASSWORD=...

./build-fa.sh                 # -> dist/fa-portal-v2.apk
./gradlew testDebugUnitTest   # unit tests
```

Package id: `com.fastautomate.agent`. Minimum Android 8.0 (API 26).

## License

AGPL-3.0, see [LICENSE](./LICENSE). FastAutomate v2 is built on
[Mobilerun Portal](https://github.com/droidrun/mobilerun-portal) by Niels Schmidt, whose accessibility
service, screen reading and device actions it extends.
