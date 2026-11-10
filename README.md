# TV Remote (ADB)

An ultra-lightweight (~34 KB), standalone Android phone remote **built and optimized specifically for low-RAM TVs (512 MB – 1 GB RAM) such as Croma Smart TVs and other local/budget AOSP Android TVs** over a phone hotspot or local Wi-Fi — with **no PC needed** and **no heavy companion app running on the TV**.

## Why it works smoothly on 512 MB / 1 GB RAM TVs

* **Zero TV-side APK or background service:** Nothing gets installed as a persistent TV app, so it uses ~0 background RAM when idle and doesn't slow down launcher navigation or video playback.
* **Fast key injection (`tvin.dex`):** Instead of spawning `input keyevent` (~800 ms Zygote startup lag per button press on low-RAM CPUs), it runs a tiny persistent `TvInputServer` shell loop (`app_process`) over ADB TCP for near-instant D-pad, volume, and text input.
* **Works over phone hotspot:** Auto-discovers your TV on port `5555` even when Android's mobile hotspot rotates the local subnet prefix.
* **No PC required after install:** Runs completely standalone on any Android 8.0+ phone.

## Features

* **Digital Remote:** Power, Home, Back, D-Pad + OK, Play/Pause, Volume +/-, Mute.
* **Typing Pad:** Type or dictate text (`Type Only`, `Search ↵`, Backspace, Enter) directly into TV search bars and password boxes.
* **Voice Control & App-Aware Search:**
  * `"Open Netflix"`, `"Open Prime Video"`, `"Open YouTube"`, `"Volume up"`, `"Mute"`, `"Home"`, `"Back"`
  * Say a movie, series, or song name while **SmartTube**, **Prime Video**, or **Netflix** is open on screen and it automatically opens search and submits the query on the TV.
  * Target explicitly anytime: `"Search Mirzapur on Prime Video"`, `"Money Heist on Netflix"`, `"Search Kesariya on YouTube"`.

## Requirements & Security Warning

* Your TV and phone must be on the **same mobile hotspot or private Wi-Fi**.
* Your TV must have **ADB over TCP enabled on port `5555` without RSA auth (`ro.adb.secure=0`)** — common on Croma / CVTE / HiSilicon / uncertified AOSP smart TVs.
  * **Security warning:** Do **not** leave unauthenticated ADB port `5555` enabled on public or untrusted Wi-Fi networks, as anyone on that network can send ADB commands to the TV.
* Certified Google TV / Android TV boxes that enforce `ro.adb.secure=1` (RSA pairing) are **not** supported by this client.

## Customizing TV App Packages

Default package/activity names in `VoiceCommandParser.java` target Croma / budget AOSP setups (`app.smarttube.fdroid`, `com.amazon.amazonvideo.livingroom`, `com.netflix.mediaclient`, `in.startv.hotstar`, `com.hisilicon.miracast`, `com.cvte.tv.globalui`, `com.aurora.store`). Edit `CMD_OPEN_*` in `VoiceCommandParser.java` if your TV uses different app packages.

## Building from Source

### 1. Rebuild `app/src/main/assets/tvin.dex` (only needed if editing `tvin-src/`)

```bash
mkdir -p tvin-out
javac --release 8 -cp "$ANDROID_HOME/platforms/android-34/android.jar" -d tvin-out tvin-src/com/tvremote/tvinput/TvInputServer.java
"$ANDROID_HOME/build-tools/34.0.0/d8" --min-api 26 --output app/src/main/assets tvin-out/com/tvremote/tvinput/TvInputServer.class
mv app/src/main/assets/classes.dex app/src/main/assets/tvin.dex
```

### 2. Run Unit Tests & Build Release APK

```bash
./gradlew testReleaseUnitTest assembleRelease
```

The built APK will be at `app/build/outputs/apk/release/app-release.apk`.

*(Optional release signing: set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` environment variables; otherwise Gradle signs with your local debug key.)*
