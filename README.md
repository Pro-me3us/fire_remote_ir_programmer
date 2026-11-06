# Fire Remote IR Programmer

Fire TV app that teaches custom IR codes to the Fire TV remote's IR blaster and programs them onto the remote's buttons, using the system `devicecontrol` service on a rooted device.

You navigate a diagram of the remote with the D-pad, pick a button, and either learn an IR code with the Fire TV's own IR receiver or load a Pronto hex code. The app then copies the codes to the remote.

## Features

- **Remote diagram UI.** Every programmable button is shown on an outline of the remote, colored by state:
  - yellow: not taught yet
  - blue: taught but not copied to the remote
  - green: copied to the remote
  - red (pulsing): learning now
- **Learn mode.** Press a button on the source remote; each code is captured twice, and the two captures are checked against each other.
- **Adjustable learn tolerance.** A slider (10% to 100%, default 30%) sets how closely the two captures must match.
- **Long-press menu on any button.**
  - clear the button
  - test-blast the stored code
  - program a built-in preset (Boot menu down/up/select, Cube power on)
- **Copy codes to remote** and **clear all remote codes** buttons.
- **Command interface for adb/scripts** (`CommandActivity`), so learning, setting and pushing can be driven from a shell.

## How it works

1. The app binds to the Fire TV's `com.amazon.tv.devicecontrol` service over Binder, through a root helper process built on [libsu](https://github.com/topjohnwu/libsu).
2. IR learning goes through the service's `startIrLearning` call. Captured Pronto codes are stored per button in the app's SharedPreferences.
3. "Copy codes to remote" calls the service's `pushToRemote` with an IR code set (two codes per button, a blast count and a duty cycle) and a button mapping. The remote then holds the custom IR table in its own memory.
4. `Dtos.java` and `Definitions.java` in `com.amazon.tv.devicecontrol.commons` are hand-written stubs of the service's data classes. Their package, class and field names must match the real ones exactly, so **do not enable minification**.

## Requirements

- A Fire TV device with a Bluetooth/IR remote and IR learning support (tested on a Fire TV Cube).
- **Root access** (Magisk or similar). The app needs it to talk to the device-control service.


## Build

```bash
git clone <this repo>
cd FireRemoteIrProgrammer
./gradlew assembleDebug
```

A release build works too (`./gradlew assembleRelease`), but keep `minifyEnabled false`. Sign it with your own key or the debug key if you only sideload it.

## Usage

1. Install and launch the app, then grant root when asked.
2. Select a button on the diagram and press SELECT to learn a code, or long-press it for the options menu.
3. Point the source remote at the Fire TV and press its button when asked.
4. Repeat for each button you want to program.
5. Choose **Copy codes to remote** to write the table to the remote.

For hand-entered codes, use the long-press menu's Pronto/preset options.

### adb commands

The app can be driven from a shell, for example:

```bash
adb shell am start -n com.prome3us.irprog/.CommandActivity --es cmd learn --es fn VOLUME_UP
adb shell am start -n com.prome3us.irprog/.CommandActivity --es cmd push
adb logcat -s IRProg
```

Supported commands include `status`, `learn`, `set`, `push`, `clear` and `restore`.

## Known limitations

- **Fire OS can overwrite the table.** Changing certain settings (for example, the Alexa Home Theater audio output) makes the OS run a full remote sync, which replaces your custom IR table. Program the remote last, or run **Copy codes to remote** again afterwards.
- Only values the remote's custom table supports can be set: two codes per button, a global repeat (blast) count and a global duty cycle. Per-button repeat counts and device-level options such as volume increment are not part of the remote's table.
- Behavior depends on the Fire OS version, since the interface is undocumented.

## License

Add a license of your choice here (for example MIT).

## Acknowledgements

Thanks to signde for getting the app to work with Magisk
