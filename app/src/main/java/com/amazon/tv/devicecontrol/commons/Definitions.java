package com.amazon.tv.devicecontrol.commons;

/**
 * Wire-compatible copies of the enums inside the Fire TV "devicecontrol" service.
 *
 * Java serializes enums by NAME ONLY, but the fully-qualified class name has to match what the
 * service has (com.amazon.tv.devicecontrol.commons.Definitions$DeviceFunction etc.), so this file
 * must stay in this exact package. Only the constants this app uses are declared; the service's
 * own enums have many more (DeviceFunction alone has several hundred). Do NOT run R8/ProGuard over
 * this package (minify is off in build.gradle) - an enum constant the service doesn't have an exact
 * name match for will fail Java deserialization on the service side.
 *
 * RemoteButton's full 20-value list (names only) was read directly from the service APK's compiled
 * enum constants, so it's exact.
 */
public final class Definitions {
    private Definitions() {}

    public enum DeviceFunction {
        VOLUME_UP, VOLUME_DOWN, MUTE_TOGGLE, POWER_TOGGLE,
        DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_OK,
        PLAY_PAUSE, FORWARD, REVERSE,
        BACK, MENU_MAIN, CHANNEL_UP, CHANNEL_DOWN, GUIDE,
        // Repurposed as distinct internal join-keys for CUSTOM_A/B/C (see RemoteButton javadoc below)
        MACRO_1, TOOLS, OPTIONS
    }

    public enum RemoteButton {
        POWER, VOLUME_UP, VOLUME_DOWN, MUTE,
        UP, DOWN, LEFT, RIGHT, SELECT,
        PLAY_PAUSE, FAST_FORWARD, REWIND,
        BACK, MENU, CHANNEL_UP, CHANNEL_DOWN, GUIDE_LIVE,
        CUSTOM_A, CUSTOM_B, CUSTOM_C
    }

    /**
     * Default DeviceFunction for each RemoteButton, read from the service's own
     * Definitions$RemoteButton static initializer (its enum constructor bakes one in per constant).
     *
     * ONE EXCEPTION: the service's own defaults map CUSTOM_A, CUSTOM_B and CUSTOM_C all to the SAME
     * DeviceFunction.NOT_IN_USE. The server joins an IrCode to a button purely by DeviceFunction
     * equality (CustomSyncPayloadProvider.getButtonMap), so three buttons sharing one function value
     * would each receive ALL THREE codes instead of their own - not usable for independent custom
     * codes. We give each custom button its own otherwise-unused tag instead (MACRO_1/TOOLS/OPTIONS -
     * chosen only because they exist in the real enum and aren't used by any other button here; the
     * tag has no semantic meaning to the service beyond "same tag = same code").
     */
    public static DeviceFunction defaultFunctionFor(RemoteButton b) {
        switch (b) {
            case POWER: return DeviceFunction.POWER_TOGGLE;
            case VOLUME_UP: return DeviceFunction.VOLUME_UP;
            case VOLUME_DOWN: return DeviceFunction.VOLUME_DOWN;
            case MUTE: return DeviceFunction.MUTE_TOGGLE;
            case UP: return DeviceFunction.DPAD_UP;
            case DOWN: return DeviceFunction.DPAD_DOWN;
            case LEFT: return DeviceFunction.DPAD_LEFT;
            case RIGHT: return DeviceFunction.DPAD_RIGHT;
            case SELECT: return DeviceFunction.DPAD_OK;
            case PLAY_PAUSE: return DeviceFunction.PLAY_PAUSE;
            case FAST_FORWARD: return DeviceFunction.FORWARD;
            case REWIND: return DeviceFunction.REVERSE;
            case BACK: return DeviceFunction.BACK;
            case MENU: return DeviceFunction.MENU_MAIN;
            case CHANNEL_UP: return DeviceFunction.CHANNEL_UP;
            case CHANNEL_DOWN: return DeviceFunction.CHANNEL_DOWN;
            case GUIDE_LIVE: return DeviceFunction.GUIDE;
            case CUSTOM_A: return DeviceFunction.MACRO_1;   // NOT the service default (see javadoc)
            case CUSTOM_B: return DeviceFunction.TOOLS;     // NOT the service default (see javadoc)
            case CUSTOM_C: return DeviceFunction.OPTIONS;   // NOT the service default (see javadoc)
            default: throw new AssertionError("unmapped RemoteButton: " + b);
        }
    }
}
