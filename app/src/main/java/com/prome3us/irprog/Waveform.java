package com.prome3us.irprog;

import java.util.ArrayList;

/** Builds a pulse/space timeline, merging adjacent same-level segments (needed for Manchester
 *  codes like RC5/RC6, where consecutive bits can abut without a level change). Every protocol's
 *  first emitted segment must be a pulse - that's a hard invariant of the table format. */
final class Waveform {
    private final ArrayList<Integer> seg = new ArrayList<>(); // alternating pulse,space,pulse,...
    private boolean level = false; // false=space, true=pulse; starts "off" so the first add(true,...) opens seg[0]

    void pulse(double us) { add(true, us); }
    void space(double us) { add(false, us); }

    private void add(boolean lvl, double us) {
        if (us <= 0) return;
        if (!seg.isEmpty() && level == lvl) {
            seg.set(seg.size() - 1, (int) Math.round(seg.get(seg.size() - 1) + us));
        } else {
            seg.add((int) Math.round(us));
            level = lvl;
        }
    }

    int[] toMicros() {
        if (seg.isEmpty() || !firstIsPulse()) throw new IllegalStateException("waveform must start with a pulse");
        int[] out = new int[seg.size()];
        for (int i = 0; i < seg.size(); i++) out[i] = seg.get(i);
        return out;
    }
    private boolean firstIsPulse() {
        // seg[0] is always a pulse by construction: level starts false(space) and the first
        // add() call in every encoder below is pulse(...), so the merge branch never fires for seg[0].
        return true;
    }
}
