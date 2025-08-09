# Uma Musume Automation For Android

![GitHub commit activity](https://img.shields.io/github/commit-activity/m/steve1316/uma-android-automation?logo=GitHub) ![GitHub last commit](https://img.shields.io/github/last-commit/steve1316/uma-android-automation?logo=GitHub) ![GitHub issues](https://img.shields.io/github/issues/steve1316/uma-android-automation?logo=GitHub) ![GitHub pull requests](https://img.shields.io/github/issues-pr/steve1316/uma-android-automation?logo=GitHub) ![GitHub](https://img.shields.io/github/license/steve1316/uma-android-automation?logo=GitHub)

> Discord here: https://discord.gg/5Yv4kqjAbm

This Android application written in Kotlin is designed to fully automate a run of Uma Musume Pretty Derby by offering a set of options to customize what event rewards the bot should prioritise, stats to focus on, etc. Building on top of the work done for ![Uma Android Training Helper](https://github.com/steve1316/uma-android-training-helper), this aims to solve the issue of spending too much hands-on time with completing a run for Uma Musume Pretty Derby.

https://user-images.githubusercontent.com/18709555/125517168-61b72aa4-28be-4868-b160-2ff4aa4d73f6.mp4

# Disclaimer

This project is purely for educational purposes to learn about Android automation and computer vision - basically a fun way to practice coding skills. Any usage is at your own risk. No one will be responsible for anything that happens to you or your own account except for yourself.

# Requirements

-   Android Device or Emulator (Nougat 7.0+)
    -   For the best performance and stability on phones, the device needs to be at 1080p display resolution. The speed is also best at 1080p and for other resolutions, it becomes dependent on the manual scale that you can set in the settings. Right now it defaults to 1.0 which uses 1080p as the baseline. You can determine what scales may be good for you with the template match test that you can enable in the settings as well.
        -   If you change the display resolution while the overlay button is still active, you will need to restart the app in order for the display changes to persist to the `MediaProjection` service.
    -   Tested emulator was on Bluestacks 5 (Pie 64-bit). The following setup is required:
        -   Portrait Mode needs to be forced on always.
        -   Bluestacks itself needs to be updated to the latest version to avoid Uma Musume crashing.
        -   In the Bluestacks Settings > Phone, the predefined profile needs to be set to a modern high-end phone like the Samsung Galaxy S22.
        -   Device setup:
            -   4 CPU Cores
            -   4GB Memory
            -   Display resolution set to Portrait 1080 x 1920
            -   Pixel density 240 DPI (Medium)

# Features

-   [x] Able to complete a run from start/midway to its completion.
-   [x] Settings to customize preferences and stat prioritization for Training Events.
-   [x] Handles races, both via skipping and running the race manually.
-   [x] Runs extra races to farm fans when enabled in the settings.
-   [x] A multitude of settings to configure including setting preferred stat targets per distance.

# Instructions

1. Download the .apk file from the `Releases` section on the right and install it on your Android device.
2. Once you have it running, fill out the required section marked with \* in the Settings page of the application. That would be the selection of the Character under the Training Event section.
3. Now go back to the Home page after you have finished customizing the settings. The settings you have selected will be shown to you in the text box below the `Start` button.
4. Now tap on the `Start` button. If this is the first time, it will ask you to give the application `Overlay` permission and starting up the `Accessibility` service.
    1. You are also required to enable `Allow restricted settings` in the `App Info` page of the app in the Android Settings.
5. Once it is enabled, tapping on the `Start` button again will create a popup asking if you want `MediaProjection` to work on `A single app` or `Entire screen`. Select the `Entire screen` option. A floating overlay button will now appear that you can move around the screen.
6. Navigate yourself to the screen below that shows available options like Rest, Train, Buy Skills, Races, etc.

> ![main screen](https://user-images.githubusercontent.com/18709555/125517626-d276cda0-bffa-441d-a511-a222237837a1.jpg)

7. Press the overlay button to start the automation process. For enabling app notifications, it is recommended to have a notification style that is small enough that it does not fully cover the top part of the screen where it contains the date, energy, turn number, etc. Or disable notifications if you do not want to worry about it.
    1. The bot will not start on any other screen than what is shown above.

## To view Logs in Real-time

1. Install `Android Studio` and create any new project or open an existing one in order for the `Logcat` console to appear at the bottom.
2. Connect your Android device to your computer:
   - **USB Connection:** Enable `Developer Options` and `USB Debugging` on your device, then connect via USB cable.
   - **Wireless Connection:** In Developer Options, enable `Wireless debugging` and pair your device using the pairing code or QR code.
   - **Bluestacks or other emulators** In the emulator settings, there is usually an option to open up to allow ADB wireless connection on `127.0.0.1:5555`. Enabling that option should be enough but if Android Studio still does not see it, you can open up a terminal like `cmd` and type `adb connect 127.0.0.1:5555` and it should say `connected to 127.0.0.1:5555`. You may need to type `adb disconnect` to disconnect all ADB connections beforehand for a fresh slate.
3. In Android Studio's Logcat console at the bottom of the window, select your connected device from the device dropdown menu.
4. Filter the logs by typing `package:com.steve1316.uma_android_automation [UAA]` or just `[UAA]` in the search box to see only the logs from this app.
5. Run the app - you'll now see all of its logs appear in real-time as it runs.

## To set the phone's resolution to 1080p (faster and more accurate)

**NOTE:** this only works when downscaling. If your device official resolution is lower than 1080p it will most likely not work.
1. Install the [**aShell You**](https://github.com/DP-Hridayan/aShellYou) app. This allows you to run adb commands locally, but requires [**Shizuku**](https://github.com/RikkaApps/Shizuku).
2. Install [**Shizuku**](https://github.com/RikkaApps/Shizuku), then start it by following [these instructions](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging).
3. With **Shizuku** started, you can then use **aShell You** to send the following adb commands:
   - **Change resolution to 1080p:** `wm size 1080x1920 && wm density 240`
   - **Revert changes:** `wm size reset && wm density reset`

    You can also bookmark the commands for your own convenience.

Make sure to use 1.0 scaling, as well as 80% confidence for best results in 1080p.


# For Developers

1. Download and extract the project repository.
2. Go to `https://opencv.org/releases/` and download OpenCV (make sure to download the Android version of OpenCV) and extract it. As of 2025-07-20, the OpenCV version used in this project is 4.12.0.
3. Create a new folder inside the root of the project repository named `opencv` and copy the extracted files in `/OpenCV-android-sdk/sdk/` from Step 2 into it.
4. Open the project repository in `Android Studio`.
5. Open up the `opencv` module's `build.gradle`. At the end of the file, paste the following JVM Toolchain block:

```kotlin
// Explicitly set Kotlin JVM toolchain to Java 17 to match the OpenCV module's Java target.
// Without this, Kotlin defaults to JVM 21 (especially with Kotlin 2.x), which causes a build failure:
// "Inconsistent JVM Target Compatibility Between Java and Kotlin Tasks".
// See: https://kotl.in/gradle/jvm/toolchain for details.
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
```

6. You can now build and run on your Android Device or build your own .apk file.
7. You can set `universalApk` to `true` in the app's `build.gradle` to build a one-for-all .apk file or adjust the `include 'arm64-v8a'` to customize which ABI to build the .apk file for.

# Technologies Used

1. [jpn.traineddata from UmaUmaCruise by @amate](https://github.com/amate/UmaUmaCruise)
2. [eng.traineddata from tessdata](https://github.com/tesseract-ocr/tessdata)
3. [MediaProjection - Used to obtain full screenshots](https://developer.android.com/reference/android/media/projection/MediaProjection)
4. [AccessibilityService - Used to dispatch gestures like tapping and scrolling](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
5. [OpenCV Android - Used to template match](https://opencv.org/releases/)
6. [Tesseract4Android - For performing OCR on the screen](https://github.com/adaptech-cz/Tesseract4Android)
7. [string-similarity - For comparing string similarities during text detection](https://github.com/rrice/java-string-similarity)
8. [AppUpdater - For automatically checking and notifying the user for new app updates](https://github.com/javiersantos/AppUpdater)
