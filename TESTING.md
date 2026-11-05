# Testing ClipCascade Plus

## Automated (GitHub Actions)

`Protocol compatibility` runs on every relevant push, against the official server image and the desktop client's own crypto and STOMP code:

- Encryption and login hash compatibility, in both directions.
- Clips through the real server: text, Unicode, large, empty, image and file types.
- Dead session, bad credentials, half-dead connection and dropped connection.
- Loop prevention, and the sensitive-clip expiry logic.

None of this runs on Android. Everything below needs the phone.

## What the emulator test covers

`Emulator end-to-end` runs the release build on stock Android 15 against the official server. It logs in through the real UI, then checks:

- Clips both ways, through the phone's clipboard and the desktop client's code.
- Recovery after `kill -9`, a server restart, airplane mode and a swipe from recents.
- Starting and connecting after a real reboot, without opening the app.

It doesn't cover One UI, Samsung's battery management, Android 16, Shizuku or the 5-minute expiry timer on the device.

## Before you test

1. Install the APK. Log in with the same username, password and salt settings as your other devices.
2. In the app, open **Setup checklist and status**, or tap **Status** on the notification. Complete every item you can.
3. If READ_LOGS is granted, Android asks to "access all device logs" when sync starts with the app open. Choose **Allow one-time access**, or the logcat trigger stays silent. It may ask again after each reboot.
4. Note the **Copy capture** line. It tells you which provider is running.

To report a failure, tap **Share log** on the same screen. The log never contains clip content or passwords.

## Questions only the phone can answer

Record the answers in `NOTES.md`.

| # | Check | How | Result |
|---|---|---|---|
| 1 | Does the Shizuku listener attach on One UI 8.5? | Log shows `Shizuku clipboard listener attached`, and not `Shizuku listener failed` | |
| 2 | Does each copy show a "pasted from clipboard" message? | Copy with Shizuku active and watch the screen | |
| 3 | Are images and files read through Shizuku? | Copy an image from Gallery or Samsung Internet | |
| 4 | Do apps mark passwords as sensitive? | Copy from your password manager. Log shows `Sensitive clip: expires in 5 min` | |
| 5 | Does an empty clip clear the Windows and Mac clipboards? | Copy a sensitive clip on the phone, wait 5 minutes, paste on each computer | |
| 6 | Does the keyboard switch work, and does it flicker? | Stop Shizuku, then copy while the keyboard is open | |
| 7 | Does the previous keyboard always come back? | After check 6, confirm Samsung Keyboard is still the default | |
| 8 | Does the logcat trigger fire on Android 16? | With READ_LOGS granted and the app opened once, check 6 sends a clip | |
| 9 | Does a service restarted by Android stay running? | Kill the process (below), then check the log | |
| 10 | Do pings survive your reverse-proxy tunnel? | Leave it idle for an hour and count `Retrying` lines in the log | |

## Acceptance tests

| Test | How | Pass |
|---|---|---|
| Airplane mode | Turn on for 1 minute, then off | Notification shows "No network", then "Connected" within seconds |
| Network switch | Toggle Wi-Fi with mobile data on, or walk out of range | Reconnects without action |
| Doze | `adb shell dumpsys deviceidle force-idle`, send a clip from the PC, then `adb shell dumpsys deviceidle unforce` | Clip arrives after leaving idle, and the connection recovers |
| Reboot | Restart the phone, unlock it, don't open the app | Notification appears and shows "Connected" |
| Capture after reboot | Copy something before restarting Shizuku | **Expected to fail until the app is opened.** See NOTES.md section 13. Then restart Shizuku: the log should show it attach with no other action |
| Server outage | Stop the server for 10 minutes, then start it | Reconnects without action |
| Process kill | `adb shell am kill com.clipcascade.plus` while it's in the background, or `adb shell kill` on its process | Service returns |
| Swipe from recents | Swipe the app away | Service stays or returns within seconds |
| Force stop | Settings > Apps > Force stop | **Stays stopped until the app is opened.** This is an Android rule |
| Content | Copy text, an image and a small file, screen on, in several apps | Each arrives on the PC and the Mac |
| Receiving | Copy text and an image on the PC. Send a file from the PC | Text and image reach the clipboard. The file lands in Downloads |
| Sensitive expiry | Copy a password on the phone, wait 5 minutes | Cleared on the phone, PC and Mac. If you copied something newer first, that stays |
| Official clients | Use the unmodified desktop apps throughout | They work as before |

## 24-hour soak test

Use the phone normally for 24 hours, then:

- Count the times you had to open the app to reconnect. The target is zero.
- Note battery use for the app under Settings > Battery.
- Share the log if anything went wrong.

**Done means:** 24 hours of normal use with no manual reconnects, phone copies reaching the PC and Mac automatically, PC and Mac copies reaching the phone, and the official desktop clients and server unchanged.
