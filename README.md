<p align="center">
  <img src=".github/banner.svg" width="100%" alt="Reference Tools · 5G Media Streaming (5GMS) Architecture: 5GMS Media Session Handler">
</p>

<p align="center">
  An Android implementation of the downlink 5GMS Media Session Handler, a 5GMS Client component
  defined in ETSI TS 126.501.
</p>

<p align="center">
  <img alt="Status" src="https://img.shields.io/endpoint?url=https%3A%2F%2Fwww.5g-mag.com%2Fbadges%2Fstatus%2Frt-5gms-media-session-handler.json">
  <a href="https://github.com/5G-MAG/rt-5gms-media-session-handler/releases"><img alt="Version"
    src="https://img.shields.io/github/v/release/5G-MAG/rt-5gms-media-session-handler?label=Version"></a>
  <a href="License.md"><img alt="License: 5G-MAG Public License v1.0"
    src="https://img.shields.io/badge/License-5G--MAG%20PL%20v1.0-blue"></a>
</p>

<p align="center">
  <a href="https://www.5g-mag.com/reference-tools/5gms/">Project page</a> &nbsp;&middot;&nbsp;
  <a href="https://github.com/5G-MAG/rt-5gms-media-session-handler/issues">Issues</a> &nbsp;&middot;&nbsp;
  <a href="https://www.5g-mag.com/contributing">Contributing</a>
</p>

---

## At a glance

|  |  |
|---|---|
| **Implements** | ETSI TS 126.501 (no version stated in the repository) |
| **Part of** | [5G Media Streaming (5GMS) Architecture](https://www.5g-mag.com/reference-tools/5gms/), alongside [cmcd-toolkit](https://github.com/5G-MAG/cmcd-toolkit), [rt-5gc-service-consumers](https://github.com/5G-MAG/rt-5gc-service-consumers), [rt-5gms-application](https://github.com/5G-MAG/rt-5gms-application), [rt-5gms-application-function](https://github.com/5G-MAG/rt-5gms-application-function), [rt-5gms-application-provider](https://github.com/5G-MAG/rt-5gms-application-provider), [rt-5gms-application-server](https://github.com/5G-MAG/rt-5gms-application-server), [rt-5gms-common-android-library](https://github.com/5G-MAG/rt-5gms-common-android-library), [rt-5gms-examples](https://github.com/5G-MAG/rt-5gms-examples), [rt-5gms-media-stream-handler](https://github.com/5G-MAG/rt-5gms-media-stream-handler), [rt-cmmf-encoder](https://github.com/5G-MAG/rt-cmmf-encoder), [rt-media-origin](https://github.com/5G-MAG/rt-media-origin) |

## Introduction

This repository is the downlink 5GMS Media Session Handler, an Android application. It retrieves its
configuration, the Service Access Information, from the
[5GMSd AF](https://github.com/5G-MAG/rt-5gms-application-function) at reference point M5d, and uses
it to activate and exploit the 5GMSd features currently provisioned. Through M6 it exposes APIs to
the 5GMSd-Aware Application and to the Media Player for downlink streaming.

More information is on the [project page](https://www.5g-mag.com/reference-tools/5gms/).

### About the implementation

The Media Session Handler is implemented as an Android Messenger Service. It communicates over
Inter Process Communication (IPC) with other Android libraries and applications, such as the
[Media Stream Handler](https://github.com/5G-MAG/rt-5gms-media-stream-handler) and the
[5GMSd-Aware Application](https://github.com/5G-MAG/rt-5gms-application).

## Specification

The repository names ETSI TS 126.501 but no version.

Clause-by-clause coverage, and what is still absent, is recorded on the project page rather than
here: <https://www.5g-mag.com/reference-tools/5gms/>

## Install dependencies

The Media Session Handler needs the
[Common Android Library](https://github.com/5G-MAG/rt-5gms-common-android-library) to run. It is
declared as a Maven dependency in the `build.gradle` of the `app` module:

````
dependencies {
   implementation 'com.fivegmag:a5gmscommonlibrary:1.3.0'
}
````

The version number (`1.3.0` in the example above) might differ, depending on the version of the
Media Session Handler.

Install the library by following the installation guide in its README, and publish it to the local
Maven repository:

* [Common Android Library](https://github.com/5G-MAG/rt-5gms-common-android-library#publish-to-local-maven-repository)

## Downloading

Release versions are on the [releases](https://github.com/5G-MAG/rt-5gms-media-session-handler/releases)
page. To get the source, clone the repository:

```
cd ~
git clone https://github.com/5G-MAG/rt-5gms-media-session-handler.git
```

## Building

To generate the `apk` bundles, run this command from the repository root:

````
./gradlew assemble
````

The `apk` bundles are written to `app/build/outputs/apk`: the debug build in the `debug` folder and
the release build in the `release` folder.

## Installing

To install the `apk` on an Android device:

1. Connect the Android device to your development machine.
2. Call `adb devices` to list the available Android devices. The output should look like this:

   ````
   List of devices attached
   CQ30022U4R	device
   ````

3. Install the `apk` on the target
   device: `adb -s <deviceID> install -r app/build/outputs/apk/debug/app-debug.apk`. The `-r` option
   reinstalls an existing app and keeps its data.

## Running

Once installed, the Media Session Handler can be started from the Android app selection screen, or
from the command line: `adb shell am start -n com.fivegmag.a5gmsmediasessionhandler/com.fivegmag.a5gmsmediasessionhandler.MainActivity `

## Development

This project follows the
[Gitflow workflow](https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow).
The `development` branch is the integration branch for new features, so switch to it before starting
work on a new feature.

## Contributing

Contributions are welcome. How to raise an issue, fork the repository and open a pull request, and
the Contributor License Agreement required before code can be merged, are described at
<https://www.5g-mag.com/contributing>.

## License

Distributed under the 5G-MAG Public License v1.0. See [License.md](License.md).
