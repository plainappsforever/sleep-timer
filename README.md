# Sleep Timer Trial

Fade out and pause music, podcasts or video at bedtime. No ads, no account.

Sleep Timer stops what you are playing at the time you choose, so you can fall asleep to music, a podcast, an audiobook or a video without it running all night.

**Get it:** [Google Play](https://plainapps.dfdsfdsfsdf.workers.dev/apps/sleep-timer/) · [GitHub Releases](https://github.com/plainappsforever/sleep-timer/releases) ·
[Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/plainappsforever/sleep-timer)

**Status:** first store release pending.

## Our promise

**Free, no ads, no tracking, no accounts.** This app keeps your data on your phone. It has no analytics, no crash
reporter, no advertising SDK and no sign-in. We will not sell this app or this project to a company
that changes any of this.

Why we write it down: in 2023 a popular open-source Android suite was sold to an ad-funded app company and its store
listings started saying "Contains ads"; its community had to fork it to keep a clean version. The source code of
Sleep Timer Trial is published under GPL-3.0, so if this promise were ever broken, anyone could continue the last
version that kept it.

## What it does

Pick a duration (or "until 23:30") and tap Start. When the time is up, the volume fades down, then the app pauses the player, then it puts your volume back. Want to be sure before you fall asleep? "Try it now" runs a 3-second test on whatever is playing and tells you how it was stopped.

WHAT IT DOES
• Fade, then pause: it sends the media pause button first, then takes over audio focus. If a player ignores both, it mutes the volume and tells you that it could only mute.
• Named presets (up to 8), each with its own fade length.
• Notification with the end time and three buttons: -5 min, +15 min, End now.
• Start from a Quick Settings tile, a home-screen widget or an app-icon shortcut.
• Shake to extend in the last minutes (optional).
• Automatic start every night at a time and on the days you choose (optional).
• Bedtime volume: lowers the media volume when the timer starts, never raises it (optional).
• Goodnight: the screen dims step by step before the end, never brighter, and your own brightness returns when the screen is off (optional).

NO ADS, NO ACCOUNT, NO INTERNET
The app has no internet permission, no ads, no analytics and no account. Your timers and settings stay on your phone.

OPTIONAL SPECIAL ACCESS
The timer itself needs none of these. Each one is asked for only when you switch on the feature that needs it, after a screen that explains why. You can say no and keep using the timer. You can turn any of them off again in Android Settings.
• Accessibility (Lock screen at end; optionally go to the Home screen first; Goodnight's dark and warm layer): used only to lock the screen the way the power button does and to draw a dark layer. It does not read or record your screen or anything you type. Android shows its own standard warning for every accessibility service; this one is set up to read nothing.
• Notification access (Enhanced compatibility): used only to see which media players are playing so they can be paused directly. The app never reads or stores your notifications.
• Do Not Disturb access: turns on its own Do Not Disturb rule "Sleep timer" when a timer starts, until a morning time you set. Your alarm still rings.
• Modify system settings: sets the screen timeout to 15 seconds after the pause, or lowers the brightness for Goodnight, and puts your values back once the screen is off.

GOOD TO KNOW
• Some players ignore every pause command. The app then mutes them and says so in its result notification.
• Needs Android 8 or newer; locking the screen needs Android 9 or newer.
• This is a timer for your media. It is not a sleep tracker and makes no health claims.

## Privacy

- All data is stored locally (SQLite in the app's private storage). Nothing is uploaded.
- Files leave the phone only when you share or export them yourself.
- Privacy policy: https://plainapps.dfdsfdsfsdf.workers.dev/apps/sleep-timer/privacy

## Build from source

The app identifier `com.tim.sleeptimer` is a placeholder: the identifier changes before the first store release.

A native Android app (Kotlin, Gradle). You need JDK 17 and the Android SDK (Android Studio installs
both; set `ANDROID_HOME`). Android only: there is no iOS version.

```sh
git clone https://github.com/plainappsforever/sleep-timer.git
cd sleep-timer
./gradlew test                 # unit tests
./gradlew assembleRelease      # APK in app/build/outputs/apk/release/
```

Builds made this way are signed with your key, not ours, so they cannot update an installed store version.

## Verifying a release

Every GitHub release lists SHA-256 checksums (`SHA256SUMS.txt`) and the APKs are signed with one key per app.
Certificate SHA-256 fingerprint: `not yet published on Android`

## How this app was made

Sleep Timer Trial was written with substantial help from an AI coding assistant (Claude Code), directed, reviewed and
tested by the maintainer. We say this openly because some stores and users care about it.

## Licence

Copyright (C) 2026 Farija Retkoceri

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, version 3 (GPL-3.0-only). See [LICENSE](LICENSE).
Third-party components keep their own licences (see `LICENSES/` and the app's licences screen where present).
Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md).
