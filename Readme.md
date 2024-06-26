<h1 align="center">5GMS Media Session Handler</h1>
<p align="center">
  <img src="https://img.shields.io/badge/Status-Under_Development-yellow" alt="Under Development">
  <img src="https://img.shields.io/github/v/tag/5G-MAG/rt-5gms-media-session-handler?label=version" alt="Version">
  <img src="https://img.shields.io/badge/License-5G--MAG%20Public%20License%20(v1.0)-blue" alt="License">
</p>

## Introduction

The 5GMS Media Session Handler is a 5GMS Client component that forms part of the 5G Media Services
framework as defined in ETSI TS 126.501.

Additional information can be found at: https://5g-mag.github.io/Getting-Started/pages/5g-media-streaming/

### 5GMS Downlink Media Session Handler

A Media Session Handler first retrieves its configuration (“Service Access Information”) from the
5GMSd AF at reference point M5d and then uses this configuration information to activate and exploit
the currently provisioned 5GMSd features. In addition, the Media Session Handler exposes APIs via M6
to the 5GMSd-Aware Application and to the
Media Player (for downlink streaming).

### About the implementation

The 5GMSd Media Session Handler is an Android application that implements functionality for 5G Media
Streaming media session handling. It is implemented as an Android Messenger Service that
communicates via Inter Process Communication (IPC) with other Android libraries and applications
such as the Media Stream Handler and the 5GMSd-Aware Application.

## Downloading

Release versions can be downloaded from
the [releases](https://github.com/5G-MAG/rt-5gms-media-session-handler/releases) page.

The source can be obtained by cloning the github repository.

```
cd ~
git clone https://github.com/5G-MAG/rt-5gms-media-session-handler.git
```

## Install dependencies

The 5GMS Media Session Handler requires
the [Common Android Library](https://github.com/5G-MAG/rt-5gms-common-android-library) to run.

It is included as Maven dependencies in the `build.gradle`:

````
dependencies {
   implementation 'com.fivegmag:a5gmscommonlibrary:1.0.0'
}
````

Note that the version number (in the example above it is set to `1.0.0`) might differ depending on the
version of the 5GMS Media Session Handler.

To install the dependencies follow the installation guides in the Readme documentation of the
project. Make sure to publish it as a local Maven repository:

* [Common Android Library](https://github.com/5G-MAG/rt-5gms-common-android-library#publish-to-local-maven-repository)

## Building

Call the following command in order to generate the `apk` bundles.

````
./gradlew assemble
````

The resulting `apk` bundles can be found in `app/build/outputs/apk`. The debug build is located
in `debug` folder the release build in the `release` folder.

## Install

To install the `apk` on an Android device follow the following steps:

1. Connect your Android device to your development machine
2. Call `adb devices` to list the available Android devices. The output should look like the
   following:

````
List of devices attached
CQ30022U4R	device
````

3. Install the `apk` on the target
   device: `adb -s <deviceID> install -r app/build/outputs/apk/debug/app-debug.apk`. Using `-r`
   we reinstall an existing app, keeping its data.

## Running

After installing the Media Session Handler application can be started from the Android app selection
screen.

As an alternative we can also run the app from the command
line: `adb shell am start -n com.fivegmag.a5gmsmediasessionhandler/com.fivegmag.a5gmsmediasessionhandler.MainActivity `

## Development

This project follows
the [Gitflow workflow](https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow)
. The `development`
branch of this project serves as an integration branch for new features. Consequently, please make
sure to switch to the `development`
branch before starting the implementation of a new feature. 
