#  Private SSH Terminal


A modern, fast, and secure desktop SSH client built with **Kotlin** and **Compose Multiplatform**. The application features a premium dark user interface heavily inspired by the popular **Termius** client.

The application supports a fully **interactive session (Shell channel)**, allowing you to manage remote servers without delay, run automation scripts (such as `x-ui` / `3x-ui` control panels), and send commands directly from your physical PC keyboard.

---

##  Key Features

* **Termius-Inspired UI**: A sleek interface utilizing a deep amethyst palette and a high-contrast terminal view designed to reduce eye strain.
* **Full Interactive Shell**: The connection is established once and held active in the background, enabling real-time bidirectional communication with the server.
* **Physical Enter Key Submission**: Commands are dispatched instantly by pressing the Enter key on your hardware keyboard—no mouse clicks required.
* **Live Connection Status Indicator**: An elegant LED indicator (`CONNECTED` / `CONNECTING` / `DISCONNECTED`) displaying the real-time state of your session.
* **Automated Log Cleaning**: An integrated regex-based parser strips out messy ANSI terminal color codes on the fly, keeping your console output perfectly clean.
* **100% Secure & Private**: A standalone, local offline application. No telemetry, no trackers, and no remote logging. Your passwords and host IPs are transmitted strictly via encrypted SSH directly to your server.

---

##  Project Architecture

The project is built on top of the modern **Kotlin Multiplatform** wizard template:
* `/shared` — contains the core business logic for SSH networking (using the `JSch` library) and shared UI layouts.
* `/desktopApp` — desktop deployment module targetting the JVM (Windows), responsible for generating native binaries, app manifests, and shortcut integration.

---

##  How to Run and Build

### Local Development Run (Hot Reload):
To launch the application from the source code within your IDE (IntelliJ IDEA):
```bash
./gradlew :desktopApp:run
```

### Build Native Windows Installer (.MSI):
To package the app into a production-ready Windows installer containing the End User License Agreement (EULA) and automated Desktop/Start Menu shortcuts:
```bash
./gradlew :desktopApp:packageMsi
```
*Once the build finishes successfully, your installer will be located at:*  
`desktopApp/build/compose/binaries/main/msi/`

---
##  Troubleshooting & Windows Protection (SmartScreen)

Since the compiled `.exe` and `.msi` installers do not contain an expensive Microsoft EV Digital Certificate, Windows Defender and **Windows SmartScreen** may flag the application upon the very first launch with a blue window saying: *"Windows protected your PC"*.

This is standard behavior for independent open-source software. To bypass this and run your app safely, follow these steps:

1. Click on the small **"More info"** link inside the blue pop-up window.
2. Click the **"Run anyway"** button that appears at the bottom.
3. If the standard Windows Firewall asks for network permissions upon connecting to your server, check the **"Private networks"** box and click **"Allow access"** to let the client communicate via SSH port 22.

Windows will remember your choice, and this message will never appear again on that computer.

## EULA License

This software is distributed under the terms of a custom **EULA (End User License Agreement)** and is provided on an "As Is" basis. The comprehensive legal text of the agreement protects the developer from liabilities regarding remote server management and is fully integrated into the MSI installer wizard (also available inside the `desktopApp/license` file).

Copyright (c) 2026 Tim Private Software. All rights reserved.
