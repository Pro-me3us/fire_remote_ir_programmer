package com.prome3us.irprog;

import java.util.EnumMap;
import java.util.Map;

/** Confirmed table-entry ids for each ProgramButton, reverse-engineered from live captures. */
final class ButtonIds {
    private ButtonIds() {}
    static final Map<ProgramButton, Integer> ID = new EnumMap<>(ProgramButton.class);
    static {
        ID.put(ProgramButton.FAST_FORWARD, 0x00);
        ID.put(ProgramButton.UP, 0x01);
        ID.put(ProgramButton.POWER, 0x02);
        ID.put(ProgramButton.PLAY_PAUSE, 0x03);
        ID.put(ProgramButton.RIGHT, 0x04);
        ID.put(ProgramButton.VOLUME_UP, 0x06);
        ID.put(ProgramButton.DOWN, 0x07);
        ID.put(ProgramButton.VOLUME_DOWN, 0x09);
        ID.put(ProgramButton.SELECT, 0x0a);
        ID.put(ProgramButton.REWIND, 0x0b);
        ID.put(ProgramButton.GUIDE_LIVE, 0x0c);
        ID.put(ProgramButton.LEFT, 0x0d);
        ID.put(ProgramButton.BACK, 0x10);
        ID.put(ProgramButton.MUTE, 0x12);
        ID.put(ProgramButton.MENU, 0x13);
        ID.put(ProgramButton.CHANNEL_UP, 0x20);
        ID.put(ProgramButton.CHANNEL_DOWN, 0x21);
        ID.put(ProgramButton.ALEXA, 0x05);
        ID.put(ProgramButton.HOME, 0x08);
        ID.put(ProgramButton.SETTINGS, 0x27);
        ID.put(ProgramButton.APP1, 0x14);
        ID.put(ProgramButton.APP2, 0x0f);
        ID.put(ProgramButton.APP3, 0x11);
        ID.put(ProgramButton.APP4, 0x0e);
        // CUSTOM_A/B/C: untested (no Pro remote) - left unmapped on purpose.
    }
}
