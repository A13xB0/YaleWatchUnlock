# YaleWatchUnlock

Unlock a Yale lock straight from a Pixel Watch over Bluetooth. No phone, no cloud, no Home Assistant round trip. You hold a button on the watch, the latch goes.

I built this for my own front door (a Yale Keyless Connected night latch, YD-01-CON, with the Yale Bluetooth module, which shows up as an MD-04I). It was already in Home Assistant through the `yalexs_ble` integration, but going through HA every time felt silly when the watch has Bluetooth and is right there at the door. So this talks to the lock directly.

It's a personal project and it's set up for my door, but if you've got a Yale/August lock working in Home Assistant's Yale Access Bluetooth integration, there's a good chance it works for you too. No promises though. It's your front door, so read the safety bit below before relying on it.

## What it does

- **Widget** (on watches that support Wear OS widgets, like the Pixel Watch 4): one big button. Hold it for a second until it buzzes, let go and the door unlocks. Let go early and nothing happens.
- **Tile**: tap it to open a full-screen hold-to-unlock button.
- **App**: a plain Unlock button, plus setup and settings.

While you're holding, the watch is already finding the lock, connecting and doing the handshake. So by the time you let go it only has to send the unlock command, and it's quick.

## How it talks to the lock

The Bluetooth protocol is a Kotlin port of [yalexs-ble](https://github.com/Yale-Libs/yalexs-ble) 3.3.1, which is the version Home Assistant 2026.7 pins. I only ported what's needed: the handshake, reading the lock status, unlock, and a clean disconnect. There's no lock command, no settings, no activity log. It's an unlock button, nothing else.

Quick summary if you're curious:

- Service `fe24`, with two pairs of characteristics. The "secure" pair does the handshake (AES-ECB with your offline key), the other pair carries commands (AES-CBC with a session key).
- The handshake swaps 8 random bytes each way to build the session key.
- The CBC chain carries on across messages in both directions, so every notification has to be decrypted in order, even ones you don't care about. That one bit me.
- There's a 250 ms cooldown between commands, because apparently sending too fast can crash the lock's radio until you pull the batteries.

To make sure the port matches the original byte for byte, `tools/gen_vectors.py` runs the real Python library against a fake lock with a dummy key and records everything it sends. The Kotlin tests then replay that recording and check every byte matches.

## Safety

It's a door, so I was paranoid about a few things:

- **Never sends unlock twice.** Finding the lock, connecting and the handshake get one retry on a fresh connection if Bluetooth flakes out. Once the unlock command might have gone out, it never retries. If the reply goes missing you get "Result unclear, check the door" instead of a second attempt.
- **The hold fails safe.** If the watch misses the moment you let go, or you hold for more than 15 seconds, it just disconnects. A missed touch event can only ever mean "nothing happened".
- **Already unlocked means nothing is sent.**
- Double taps, or the tile and app pressed together, can't start two runs.
- It runs in a foreground service, so the screen going off mid-unlock doesn't kill it. If the app does crash, Android won't restart it and replay the unlock.

## Getting your key onto the watch

You need the same offline key and slot (key index) that Home Assistant already uses. You don't need to re-pair the lock or make a new key.

The way I do it: on the watch, open **Set up → Provision over ADB**. That opens a 3-minute window that accepts exactly one delivery. Then on the computer run:

```sh
tools/provision-from-ha.sh
```

The script SSHes into Home Assistant (I use the Advanced SSH & Web Terminal add-on with key auth), pulls the `yalexs_ble` entry's key, slot, name and address out of `core.config_entries`, and pipes them straight into the watch over ADB. The key never gets printed, written to a file, or put on a command line. On the watch it only accepts it from the ADB shell, and only while that window is open.

Settings you might need to change are at the top of the script: `HA_HOST`, `HA_PORT` (default 22222), `HA_USER` (default `hassio`), and `ANDROID_SERIAL` if you've got more than one device connected. Use Wireless debugging (paired); don't use old-style `adb tcpip`, which isn't encrypted.

If you'd rather not do that, there's **Set up → Enter key manually** with a hex keypad. It works, it's just tedious typing 32 characters on a watch.

On the watch, the key is encrypted with a key held in the Android Keystore (StrongBox on the Pixel Watch 4). It only works while the watch is unlocked and is excluded from backups. You can replace or delete it in Settings.

One gotcha I hit: HA stores the lock's advertised name with a trailing NUL byte (it's 6 characters plus `\0`). The app strips that before matching, but if you're poking at this yourself, that's why the name looks one character too long.

## Building

You need JDK 21 and the Android SDK with platform 37. The widget library wants compileSdk 37 and AGP 9.

```sh
./gradlew :wear:testDebugUnitTest :wear:assembleDebug
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Then on the watch: open the app, allow Nearby devices, provision the key, and add the widget or tile.

## Layout

```
wear/src/main/kotlin/uk/co/hectospark/yaleunlock/
  protocol/    the yalexs-ble port (frames, checksums, ciphers, session)
  ble/         GATT connection and scanning
  unlock/      prepare/unlock/disconnect logic, foreground service
  credential/  key storage and the ADB provisioning bit
  widget/      the hold-to-unlock widget
  tile/        the tile
  ui/          the app screens
tools/         provisioning script and the test-vector generator
```

## Caveats

- I've only tested it on my own lock and my own watch (Pixel Watch 4). Other Yale/August locks that work with `yalexs_ble` should be fine, but I haven't tried them.
- The Wear widget library (`androidx.glance.wear`) is still in alpha, so the widget could break on a future watch update. The tile and app don't depend on it.
- If Home Assistant happens to be connected to the lock at that moment, the lock won't advertise and the watch can't see it. HA lets go after about 5 seconds, so just try again.

Not affiliated with Yale, August or Assa Abloy. Use at your own risk.
