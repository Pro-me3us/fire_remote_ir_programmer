package com.prome3us.irprog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.EditText;



import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * Learn IR codes (or type Pronto codes in by hand) and program them onto any of the Fire TV
 * remote's buttons via the system devicecontrol service.
 *
 * Everything is also drivable from a shell, e.g.:
 *   adb shell am start -n com.prome3us.irprog/.CommandActivity --es cmd learn --es fn VOLUME_UP
 *   adb logcat -s IRProg
 * See README.md for the full command list and the full button list.
 */
public class MainActivity extends Activity {

    static final String TAG = "IRProg";

    // Stock app passes this as MILLISECONDS to startIrLearning (confirmed: getIrReceivingTimeoutMs()
    // multiplies the "ir_receiving_timeout": 10 (seconds) config value by 1000; real call sites use
    // 360000-3600000 ms). 30s is a reasonable default for a deliberate "aim and press now" flow.
    private static final int DEFAULT_LEARN_TIMEOUT_MS = 30_000;
    // Mirrors the stock app's own flow (it always asks for a 2nd press - see
    // G11_3PressSameCommandAgainFragment / IR_LEARNING_*_PRESS_THAT_BUTTON_AGAIN strings - feeding
    // both into the same two-slot Dtos.LearnedIrFunction our code1/code2 already is). We go one further
    // and require a 3rd press: a real toggle-bit protocol alternates A,B,A,B..., so a correct 3rd press
    // should land back on the SAME value as the 1st. If it lands on neither prior value, that's a
    // strong signal one of the captures was noise, not a legitimate protocol state - worth flagging
    // since the service's own validateLearned() is a stub that always returns true and checks nothing.
    private static final int DEFAULT_LEARN_PRESSES = 3;
    private static final int DEFAULT_BLAST_COUNT = 2;     // stock config "blast_repeats": 2
    private static final int DEFAULT_DUTY_CYCLE = 33;     // common IR default; override with --ei duty N

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Bundle> pendingCommands = new ArrayList<Bundle>();

    private DcsClient dcs;
    private volatile boolean connectionFailed;
    private volatile boolean destroyed;
    private SharedPreferences store;
    private volatile String activeLearnToken;

    private View scrimView;
    private LinearLayout slidePanel;
    private int pad;
    private TextView logView;
    private ScrollView logScroll;
    private RemoteDiagramView diagramView;
    private volatile ProgramButton learningInProgress; // non-null while a learn sequence is running

    // ------------------------------------------------------------------ button name parsing

    /** Accepts exact ProgramButton names plus a few common aliases. */
    private static ProgramButton parseButton(String s) {
        if (s == null) return null;
        String n = s.trim().toUpperCase(Locale.US).replace('-', '_').replace(' ', '_');
        if (n.equals("VOL_UP") || n.equals("VOLUMEUP")) n = "VOLUME_UP";
        if (n.equals("VOL_DOWN") || n.equals("VOLUMEDOWN")) n = "VOLUME_DOWN";
        if (n.equals("MIC") || n.equals("VOICE")) n = "ALEXA";
        if (n.equals("FF")) n = "FAST_FORWARD";
        if (n.equals("RW") || n.equals("FR")) n = "REWIND";
        if (n.equals("OK") || n.equals("ENTER") || n.equals("CENTER") || n.equals("DPAD_OK")) n = "SELECT";
        if (n.equals("CH_UP") || n.equals("CHUP") || n.equals("CHANNELUP")) n = "CHANNEL_UP";
        if (n.equals("CH_DOWN") || n.equals("CHDOWN") || n.equals("CHANNELDOWN")) n = "CHANNEL_DOWN";
        if (n.equals("GUIDE") || n.equals("LIVE")) n = "GUIDE_LIVE";
        try {
            return ProgramButton.valueOf(n);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = getSharedPreferences("codes", MODE_PRIVATE);
        int tolPct = Math.max(10, Math.min(100, store.getInt("learn_tol_pct", 30)));
        learnTolerance = tolPct / 100.0;
        buildUi();

        dcs = new DcsClient(this, new DcsClient.ConnectionListener() {
            @Override public void onConnected() {
                say("Connected to " + DcsClient.PKG + " through Magisk root. Ready.");
                flushPending();
            }
            @Override public void onDisconnected() {
                say("Service disconnected.");
            }
            @Override public void onError(String message) {
                connectionFailed = true;
                synchronized (pendingCommands) { pendingCommands.clear(); }
                say("ERROR: " + message);
            }
        });
        say("Requesting Magisk root access...");
        dcs.bind();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
        final String t = activeLearnToken;
        if (t != null && dcs.isConnected()) {
            try { dcs.stopIrLearning(t); } catch (RemoteException | RuntimeException ignored) {}
        }
        dcs.unbind();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        outer.setPadding(pad, pad, pad, pad);
        outer.setBackgroundColor(0xFF161820);
        getWindow().getDecorView().setBackgroundColor(0xFF161820);
        if (getActionBar() != null) {
            getActionBar().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0xFF20222f));
        }
        getWindow().setStatusBarColor(0xFF1A2540);        
        
        // No in-app title TextView here - the system title bar already shows the app's name (see
        // AndroidManifest's android:label), so a second "Fire remote IR programmer" line here was
        // just a duplicate directly below it.
        TextView hint = new TextView(this);
        hint.setText("Navigate the remote with the D-pad. Yellow = not yet taught, blue = taught but not "
                + "copied to the remote yet, green = copied to the remote, red (pulsing) = learning now. "
                + "SELECT teaches a button; long-press SELECT opens options (clear, test-blast).");
        hint.setTextSize(13);
        hint.setTextColor(0xFFC9D1E6);
        hint.setPadding(0, 0, 0, dp(16));
        outer.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        outer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- left: remote diagram ---
        // Wrapped in a plain FrameLayout for the same reason as before: RemoteDiagramView's onMeasure
        // deliberately shrinks itself to preserve the remote image's aspect ratio, and an unmodified
        // FrameLayout has no such self-shrinking logic of its own. This time the wrapper is WRAP_CONTENT
        // (not a wide weighted slot) so it hugs the diagram's own actual rendered size exactly - no
        // leftover space to center within, so the diagram sits flush against the left edge and the
        // action buttons sit immediately next to it, with the log column (the only weighted child here)
        // absorbing 100% of whatever width is left over.
        diagramView = new RemoteDiagramView(this);
        diagramView.setId(View.generateViewId());
        diagramView.setCodeStateProvider(new RemoteDiagramView.CodeStateProvider() {
            @Override public boolean hasCode(ProgramButton b) { return getCode(b, 1) != null; }
            @Override public boolean isPushed(ProgramButton b) { return MainActivity.this.isPushed(b); }
        });
        diagramView.setOnButtonSelectedListener(new RemoteDiagramView.OnButtonSelectedListener() {
            @Override public void onButtonSelected(ProgramButton b) { runAsync(cmd("learn", b)); }
        });
        diagramView.setOnButtonLongPressedListener(new RemoteDiagramView.OnButtonLongPressedListener() {
            @Override public void onButtonLongPressed(ProgramButton b) { showButtonOptionsDialog(b); }
        });

        FrameLayout diagramWrapper = new FrameLayout(this);
        diagramWrapper.addView(diagramView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        LinearLayout.LayoutParams diagramWrapperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        diagramWrapperParams.rightMargin = dp(20);
        row.addView(diagramWrapper, diagramWrapperParams);

        // --- middle: the 2 remaining action buttons (clearing a button moved to its long-press menu) ---
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.rightMargin = dp(20);
        row.addView(actions, actionsParams);

        Button copyButton = addButton(actions, "Copy codes to remote", new View.OnClickListener() {
            @Override public void onClick(View v) {
                runAsync(cmd("push", null));
            }
        });
        Button restoreButton = addButton(actions, "Clear all remote codes", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Clear all remote codes?")
                        .setMessage("This restores the remote through devicecontrol and clears the app's saved codes.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", (dialog, which) -> runAsync(cmd("restore", null))).show();
            }
        });
        Button clearLogButton = addButton(actions, "Clear log", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (logView != null) logView.setText("");
            }
        });

        // Explicitly move RIGHT from the diagram into the first action button.
        copyButton.setId(View.generateViewId());
        restoreButton.setId(View.generateViewId());
        clearLogButton.setId(View.generateViewId());

        diagramView.setNextFocusRightId(copyButton.getId());

        // Allow LEFT from any action button to return to the remote diagram.
        copyButton.setNextFocusLeftId(diagramView.getId());
        restoreButton.setNextFocusLeftId(diagramView.getId());
        clearLogButton.setNextFocusLeftId(diagramView.getId());       

        // Learn-tolerance slider: D-pad LEFT/RIGHT moves it when focused; applies to all buttons.
        final TextView tolLabel = new TextView(this);
        tolLabel.setTextSize(13);
        tolLabel.setTextColor(0xFFC9D1E6);
        tolLabel.setText("Learn tolerance: " + Math.round(learnTolerance * 100) + "%");
        actions.addView(tolLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tolBar = new SeekBar(this);
        tolBar.setId(View.generateViewId());
        tolBar.setMax(9);
        tolBar.setProgress((int) Math.round(learnTolerance * 10) - 1);
        tolBar.setFocusable(true);
        tolBar.setNextFocusLeftId(diagramView.getId());
        tolBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int pct = (p + 1) * 10;
                learnTolerance = pct / 100.0;
                tolLabel.setText("Learn tolerance: " + pct + "%");
                store.edit().putInt("learn_tol_pct", pct).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        actions.addView(tolBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        restoreButton.setNextFocusDownId(clearLogButton.getId());
        clearLogButton.setNextFocusDownId(tolBar.getId());
        
        // Measure the buttons at their natural (wrap-content) width, then give the action
        // column half of that width. The button labels will wrap as needed, and the log
        // column automatically receives the width that was freed.
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        copyButton.measure(unspecified, unspecified);
        restoreButton.measure(unspecified, unspecified);
        actionsParams.width = (int) (Math.max(copyButton.getMeasuredWidth(),
                restoreButton.getMeasuredWidth()) * 0.80f);
        actions.setLayoutParams(actionsParams);

        // --- right: log - the only weighted child in the row, so it absorbs all remaining width ---
        LinearLayout logColumn = new LinearLayout(this);
        logColumn.setOrientation(LinearLayout.VERTICAL);
        row.addView(logColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextColor(0xFFC9D1E6);
        logView.setFocusable(false);
        logScroll = new ScrollView(this);
        logScroll.setId(View.generateViewId());
        // The log must never take D-pad focus itself, and must never let anything inside it take focus
        // either - otherwise a focus search can land there (e.g. while content is scrolling/updating
        // during a learn sequence) and the currently-focused remote button loses focus to it. The
        // button should keep focus until the user explicitly navigates elsewhere.
        logScroll.setFocusable(true);
        logScroll.setFocusableInTouchMode(false);
        logScroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logColumn.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Allow RIGHT from the action buttons to enter the log.
        copyButton.setNextFocusRightId(logScroll.getId());
        restoreButton.setNextFocusRightId(logScroll.getId());
        clearLogButton.setNextFocusRightId(logScroll.getId());
        tolBar.setNextFocusRightId(logScroll.getId());

        // Allow LEFT from the log to return to the action buttons.
        logScroll.setNextFocusLeftId(copyButton.getId());
        
        final FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.addView(outer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrimView = new View(this);
        scrimView.setBackgroundColor(0xCC000000);
        scrimView.setAlpha(0f);
        scrimView.setVisibility(View.GONE);
        scrimView.setFocusable(false);
        scrimView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeButtonOptionsPanel(); }
        });
        rootFrame.addView(scrimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int panelWidthPx = (int) (getResources().getDisplayMetrics().widthPixels * 0.5f);
        slidePanel = new LinearLayout(this);
        slidePanel.setOrientation(LinearLayout.VERTICAL);
        slidePanel.setBackgroundColor(0xFF1A1D29);
        slidePanel.setPadding(pad, pad, pad, 0);
        slidePanel.setVisibility(View.GONE);
        slidePanel.setTranslationX(panelWidthPx);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelWidthPx, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.gravity = android.view.Gravity.END;
        rootFrame.addView(slidePanel, panelLp);

        setContentView(rootFrame);
        copyButton.requestFocus();
    }

    /** Long-press-on-a-button popup: test-blast the stored code from the Cube's own IR blaster, or
     *  clear the button. Replaces the old dedicated "Clear selected button" action-column button. */
    private void showButtonOptionsDialog(final ProgramButton btn) {
        boolean hasCode = getCode(btn, 1) != null;
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<Runnable> actions2 = new ArrayList<Runnable>();

        if (hasCode) {
            labels.add("Test IR code (blast from this Cube's IR emitter)");
            actions2.add(new Runnable() { @Override public void run() { runAsync(cmd("test", btn)); } });
            labels.add("Clear this button's stored code");
            actions2.add(new Runnable() { @Override public void run() { runAsync(cmd("clear", btn)); } });
        } else {
            labels.add("no code stored for " + btn + " - nothing to test or clear");
            actions2.add(new Runnable() { @Override public void run() { } });
        }

        for (final String[] p : IR_PRESETS) {
            labels.add("Program: " + p[0]);
            actions2.add(new Runnable() { @Override public void run() {
                Bundle sb = cmd("set", btn);
                sb.putString("code1", p[1]);
                sb.putString("code2", p[2]);
                runAsync(sb);
            } });
        }
        labels.add("Apply an IR protocol code...");
        actions2.add(new Runnable() { @Override public void run() { showApplyProtocolDialog(btn); } });

        openButtonOptionsPanel(btn, labels, actions2);
    }

    private void openButtonOptionsPanel(ProgramButton btn, ArrayList<String> labels, final ArrayList<Runnable> actions2) {
        slidePanel.removeAllViews();

        TextView title = new TextView(this);
        title.setText(btn.name());
        title.setTextSize(16);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, pad);
        slidePanel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        
        ScrollView optionsScroll = new ScrollView(this);
        optionsScroll.setVerticalScrollBarEnabled(false);
        LinearLayout optionsList = new LinearLayout(this);
        optionsList.setOrientation(LinearLayout.VERTICAL);
        optionsScroll.addView(optionsList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        slidePanel.addView(optionsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final View.OnKeyListener closeOnLeftOrBack = new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    closeButtonOptionsPanel();
                    return true;
                }
                return false;
            }
        };

        Button firstButton = null;
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            Button b = addButton(optionsList, labels.get(i), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    actions2.get(idx).run();
                    closeButtonOptionsPanel();
                }
            });
            b.setOnKeyListener(closeOnLeftOrBack);
            if (firstButton == null) firstButton = b;
        }

        scrimView.setVisibility(View.VISIBLE);
        slidePanel.setVisibility(View.VISIBLE);
        scrimView.animate().alpha(1f).setDuration(220).start();
        slidePanel.animate().translationX(0).setDuration(220).start();

        final Button toFocus = firstButton;
        slidePanel.post(new Runnable() { @Override public void run() { toFocus.requestFocus(); } });
    }

    private void closeButtonOptionsPanel() {
        int panelWidthPx = slidePanel.getWidth() > 0 ? slidePanel.getWidth()
                : (int) (getResources().getDisplayMetrics().widthPixels * 0.5f);
        scrimView.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() { scrimView.setVisibility(View.GONE); }
        }).start();
        slidePanel.animate().translationX(panelWidthPx).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() {
                slidePanel.setVisibility(View.GONE);
                refocusDiagram();
            }
        }).start();
    }

    private void showApplyProtocolDialog(final ProgramButton btn) {
        final IrProtocols.Protocol[] protos = IrProtocols.Protocol.values();
        final String[] protoLabels = new String[protos.length + 1];
        for (int i = 0; i < protos.length; i++) protoLabels[i] = protos[i].name();
        final int PRONTO_INDEX = protos.length;
        protoLabels[PRONTO_INDEX] = "PRONTO (paste raw code)";

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        final Spinner protoSpinner = new Spinner(this);
        protoSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, protoLabels));
        root.addView(protoSpinner);

        final TextView hint = new TextView(this);
        hint.setPadding(0, pad / 2, 0, 0);
        root.addView(hint);

        final EditText codeField = new EditText(this);
        codeField.setHint("code as hex (e.g. 20DF10EF)");
        codeField.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        root.addView(codeField);

        final EditText bitsField = new EditText(this);
        bitsField.setHint("bits (only used for Panasonic/Kaseikyo)");
        bitsField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        bitsField.setText("48");
        root.addView(bitsField);

        final EditText sonyField = new EditText(this);
        sonyField.setHint("Sony SIRC variant: 12, 15 or 20");
        sonyField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        sonyField.setText("12");
        root.addView(sonyField);

        protoSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos == PRONTO_INDEX) {
                    bitsField.setVisibility(View.GONE);
                    sonyField.setVisibility(View.GONE);
                    hint.setText("Paste a full learned/Pronto code, e.g. starting with 0000 006D ...");
                    return;
                }
                IrProtocols.Protocol sel = protos[pos];
                bitsField.setVisibility(sel == IrProtocols.Protocol.PANASONIC_KASEIKYO ? View.VISIBLE : View.GONE);
                sonyField.setVisibility(sel == IrProtocols.Protocol.SONY_SIRC ? View.VISIBLE : View.GONE);
                hint.setText(protocolHint(sel));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        hint.setText(protocolHint(protos[0]));
        bitsField.setVisibility(View.GONE);
        sonyField.setVisibility(View.GONE);

        new AlertDialog.Builder(this).setTitle("Apply an IR protocol code")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            int pos = protoSpinner.getSelectedItemPosition();
                            String pronto;
                            if (pos == PRONTO_INDEX) {
                                String raw = Pronto.normalize(codeField.getText().toString());
                                String err = Pronto.check(raw);
                                if (err != null) throw new IllegalArgumentException("invalid Pronto code: " + err);
                                pronto = raw;
                            } else {
                                IrProtocols.Protocol sel = protos[pos];
                                String codeStr = codeField.getText().toString().trim();
                                if (codeStr.toLowerCase(java.util.Locale.US).startsWith("0x")) codeStr = codeStr.substring(2);
                                long code = Long.parseLong(codeStr, 16);
                                int bits = Integer.parseInt(bitsField.getText().toString().trim());
                                int sonyVariant = Integer.parseInt(sonyField.getText().toString().trim());
                                IrProtocols.Result r = IrProtocols.encode(sel, code, bits, sonyVariant);
                                pronto = Pronto.encode(r.us, r.freqHz);
                            }
                            Bundle sb = cmd("set", btn);
                            sb.putString("code1", pronto);
                            runAsync(sb);
                        } catch (Exception e) {
                            say("Could not apply that code: " + e);
                        }
                    }
                }).show();
    }

    private static String protocolHint(IrProtocols.Protocol p) {
        switch (p) {
            case NEC: return "Enter as 0xAACC: AA=8-bit address, CC=8-bit command (inversion added automatically).";
            case NEC_EXTENDED: return "Enter the full 32-bit code as published/detected (e.g. 0xf30c7d02) - address and command are extracted automatically.";
            case SAMSUNG32: case LG32: return "Enter the full 32-bit code exactly as published for your device.";
            case SONY_SIRC: return "Enter the combined address+command bits; set the variant below (12/15/20-bit).";
            case RC5: return "Enter the 14-bit frame (2 start bits + toggle + 5-bit address + 6-bit command).";
            case RC6: return "Enter mode(3 bits)+toggle(1 bit)+address(8 bits)+command(8 bits) as one value.";
            case PANASONIC_KASEIKYO: return "Enter the full frame (set bit length below) exactly as published for your device.";
            case JVC: return "Enter the full 16-bit code (address+command, no inversion).";
            case SHARP: return "Enter the full 15-bit code.";
        }
        return "";
    }
    
    private static final String[][] IR_PRESETS = {
        {"Cube power on",
         "0000 006D 0023 0000 0158 00AA 0019 003E 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0040 0018 003E 0018 003F 0018 0040 0018 0040 0018 003E 0018 0040 0018 0015 0018 0015 0018 0015 0018 0015 0018 003F 0018 0015 0018 0015 0018 0015 0018 0040 0018 0040 0018 003F 0018 003F 0018 0015 0018 0040 0018 003F 0018 0040 0018 05DA 0157 0056 0018",
         "0000 006D 0023 0000 0158 00AA 0019 003E 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0015 0018 0040 0018 003E 0018 003F 0018 003F 0018 003F 0018 003E 0018 003F 0018 0015 0018 0015 0018 0014 0018 0015 0018 0040 0018 0015 0018 0015 0018 0014 0018 0040 0018 003F 0018 003F 0018 003F 0018 0015 0018 003F 0018 003F 0018 003F 0018 05D9 0157 0055 0018"},    
        {"Boot menu up",
         "0000 006D 0023 0000 0158 00AB 0017 0016 0017 0040 0017 0016 0017 0015 0017 0016 0017 0016 0017 0016 0017 0015 0017 0040 0017 0016 0017 0040 0017 0040 0017 0040 0017 0040 0017 0040 0017 0016 0017 0015 0017 0016 0017 0040 0017 0040 0017 0015 0017 0016 0017 0016 0017 0016 0017 0040 0017 0040 0017 0016 0017 0016 0017 0040 0017 0040 0017 0040 0017 0040 0017 0608 0158 0056 0017",
         "0000 006D 0023 0000 0158 00AB 0017 0016 0017 0040 0017 0015 0017 0016 0017 0016 0017 0016 0017 0015 0017 0016 0017 0040 0017 0016 0017 0040 0017 0040 0017 0040 0017 0040 0017 0040 0017 0016 0017 0016 0017 0016 0017 0040 0017 0040 0017 0016 0017 0016 0017 0016 0017 0015 0017 0040 0017 0040 0017 0015 0017 0016 0017 0040 0017 0040 0017 0040 0017 0040 0017 0608 0158 0056 0017"},    
        {"Boot menu down",
         "0000 006D 0023 0000 0158 00AB 0017 0016 0017 0040 0017 0015 0017 0016 0017 0016 0017 0016 0017 0015 0017 0016 0017 0040 0017 0016 0017 0040 0017 0040 0017 0040 0017 0040 0017 0040 0017 0016 0017 0040 0017 0016 0017 0016 0017 0040 0017 0040 0017 0015 0017 0016 0017 0016 0017 0015 0017 0040 0017 0040 0017 0015 0017 0016 0017 0040 0017 0040 0017 0040 0017 0608 0158 0056 0017",
         "0000 006D 0023 0000 0158 00AC 0017 0015 0017 0040 0017 0015 0017 0016 0017 0016 0017 0016 0017 0016 0017 0016 0017 0040 0017 0016 0017 0040 0017 0040 0017 0040 0017 0040 0017 0040 0017 0015 0017 0040 0017 0015 0017 0016 0017 0040 0017 0040 0017 0016 0017 0016 0017 0016 0017 0015 0017 0040 0017 0040 0017 0015 0017 0016 0017 0040 0017 0040 0017 0040 0017 0608 0158 0056 0017"},
        {"Boot menu select",
         "0000 006D 0023 0000 0158 00AB 0017 0016 0017 0040 0017 0016 0017 0016 0017 0016 0017 0016 0017 0016 0017 0015 0017 0040 0017 0015 0017 0040 0017 0040 0017 0040 0017 0040 0017 0040 0017 0016 0017 0016 0017 0015 0017 0040 0017 0040 0017 0015 0017 0016 0017 0040 0017 0016 0017 0040 0017 0040 0017 0016 0017 0016 0017 0040 0017 0040 0017 0016 0017 0040 0017 0608 0158 0056 0017",
         "0000 006D 0023 0000 0158 00AC 0017 0015 0017 0040 0017 0016 0017 0016 0017 0016 0017 0015 0017 0016 0017 0016 0017 0040 0017 0016 0017 0040 0017 0040 0017 0040 0017 0040 0017 0040 0017 0015 0017 0016 0017 0016 0017 0040 0017 0040 0017 0016 0017 0016 0017 0040 0017 0016 0017 0040 0017 0040 0017 0016 0017 0015 0017 0040 0017 0040 0017 0015 0017 0040 0017 0608 0158 0056 0017"},
    };
    
    private Button addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setFocusable(true);
        b.setBackground(pillBackground());
        b.setTextColor(0xFFFFFFFF);
        b.setStateListAnimator(null);   // removes the default raised/shadow look
        b.setMinHeight(dp(52));        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(b, lp);
        return b;
    }

    private StateListDrawable pillBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, pill(0xFF2D3241));
        s.addState(new int[]{android.R.attr.state_pressed}, pill(0xFF2D3241));
        s.addState(new int[]{}, pill(0xFF242733));
        return s;
    }
    private GradientDrawable pill(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(40));
        g.setStroke(dp(2), 0xFF454C65);
        return g;
    }    

    /** These push View state changes onto the UI thread - cmdLearn/learnOnePress run on `worker`,
     *  and their callbacks arrive on a Binder thread, neither of which is safe for View mutation. */
    private void setDiagramLearning(final ProgramButton b) {
        if (diagramView == null) return;
        ui.post(new Runnable() { @Override public void run() { diagramView.setLearningButton(b); } });
    }
    private void refreshDiagram() {
        if (diagramView == null) return;
        ui.post(new Runnable() { @Override public void run() { diagramView.refresh(); } });
    }
    /** Defensively re-asserts focus on the diagram after any operation (learn, clear, restore, a
     *  dialog closing) that might otherwise leave focus somewhere else, such as the log. */
    private void refocusDiagram() {
        if (diagramView == null) return;
        ui.post(new Runnable() { @Override public void run() { diagramView.requestFocus(); } });
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void say(final String msg) {
        Log.i(TAG, msg);
        if (destroyed) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed || logView == null) return;
                logView.append(msg + "\n");
                logScroll.post(new Runnable() {
                    @Override public void run() {
                        int y = logView.getHeight() - logScroll.getHeight();
                        logScroll.scrollTo(0, Math.max(0, y));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ command plumbing

    private static Bundle cmd(String name, ProgramButton button) {
        Bundle b = new Bundle();
        b.putString("cmd", name);
        if (button != null) b.putString("fn", button.name());
        return b;
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        // This alias is protected by DUMP (held by adb shell/root, not normal apps).
        if (intent.getComponent() == null || !intent.getComponent().getClassName()
                .equals(getPackageName() + ".CommandActivity")) return;
        Bundle ex = intent.getExtras();
        if (ex.getString("cmd") == null) return;
        Bundle copy = new Bundle(ex);
        intent.removeExtra("cmd"); // don't re-run on rotation / re-delivery
        runAsync(copy);
    }

    private void runAsync(final Bundle b) {
        if (destroyed) return;
        String cmdName = b.getString("cmd");
        if (!dcs.isConnected() && !"show".equals(cmdName) && !"set".equals(cmdName)
                && !"clear".equals(cmdName) && !"test".equals(cmdName)) {
            if (connectionFailed) {
                say("Root service is unavailable. Grant root in Magisk and reopen the app.");
                return;
            }
            synchronized (pendingCommands) { pendingCommands.add(b); }
            say("(waiting for service connection, command queued)");
            return;
        }
        worker.submit(new Runnable() {
            @Override public void run() {
                try { execute(b); }
                catch (Throwable t) { say("ERROR: " + t); Log.e(TAG, "command failed", t); }
            }
        });
    }

    private void flushPending() {
        ArrayList<Bundle> copy;
        synchronized (pendingCommands) {
            copy = new ArrayList<Bundle>(pendingCommands);
            pendingCommands.clear();
        }
        for (Bundle b : copy) runAsync(b);
    }

    private void execute(Bundle b) throws RemoteException {
        String c = b.getString("cmd", "");
        if (c.equals("status")) {
            say("Amazon devicecontrol API version: " + dcs.getApiVersion());
            say("show_dc_settings=" + android.provider.Settings.Global.getInt(
                    getContentResolver(), "show_dc_settings", 0));
        } else if (c.equals("learn")) {
            cmdLearn(requireButton(b), b.getInt("presses", DEFAULT_LEARN_PRESSES), b.getInt("timeout", DEFAULT_LEARN_TIMEOUT_MS),
                    b.getFloat("tolerance", (float) learnTolerance));
        } else if (c.equals("set")) {
            cmdSet(requireButton(b), b.getString("code1"), b.getString("code2"));
        } else if (c.equals("push")) {
            cmdPush(b.getString("addr"));
        } else if (c.equals("ble")) {
            dcs.bluetooth(b.getString("addr"), null, bluetoothLog());
        } else if (c.equals("blepush")) {
            String[] values = b.getString("ids", "13").split(",");
            int[] ids = new int[values.length];
            java.util.HashSet<Integer> used = new java.util.HashSet<>();
            for (int i = 0; i < values.length; i++) {
                ids[i] = Integer.parseInt(values[i].trim(), 16);
                if (ids[i] < 0 || ids[i] > 255 || ids[i] == 2 || !used.add(ids[i]))
                    throw new IllegalArgumentException("Probe IDs must be unique hex bytes, excluding 02");
            }
            byte[] table = ProbeTable.buildTable(ids);
            IrTable.validate(table);
            clearPushedState();
            dcs.bluetooth(b.getString("addr"), table, bluetoothLog());
        } else if (c.equals("show")) {
            cmdShow();
        } else if (c.equals("clear")) {
            if ("all".equalsIgnoreCase(b.getString("fn"))) {
                for (ProgramButton btn : ProgramButton.values()) { putCode(btn, 1, null); putCode(btn, 2, null); }
                say("Cleared all stored codes.");
            } else {
                ProgramButton btn = requireButton(b);
                putCode(btn, 1, null);
                putCode(btn, 2, null);
                say("Cleared " + btn + ".");
            }
            refreshDiagram();
            refocusDiagram();
        } else if (c.equals("restore")) {
            cmdClearRemote(b.getString("addr"));
        } else if (c.equals("test")) {
            cmdTest(requireButton(b));
        } else {
            say("Unknown cmd '" + c + "'. Valid: learn, set, push, show, clear, restore, test, status.");
        }
    }

    /** Blasts the button's stored code1 from the Cube's OWN IR emitter (NOT the remote) - lets you
     *  verify a learned/typed code is actually correct before ever pushing it to the remote. */
    private void cmdTest(ProgramButton btn) {
        String code = getCode(btn, 1);
        if (code == null) {
            say("Nothing to test: " + btn + " has no stored code.");
            return;
        }
        say("Test-blasting " + btn + " from this Cube's IR emitter: " + code);
        say(IrDetector.describe(code));
        IrTransmitter.Result r = IrTransmitter.transmit(this, code);
        say((r.success ? "Test blast OK: " : "Test blast FAILED: ") + r.message);
        refocusDiagram();
    }

    private ProgramButton requireButton(Bundle b) {
        ProgramButton btn = parseButton(b.getString("fn"));
        if (btn == null) throw new IllegalArgumentException(
                "Missing/invalid --es fn. Valid buttons: " + java.util.Arrays.toString(ProgramButton.values()));
        return btn;
    }

    // ------------------------------------------------------------------ commands

    private static final long LEARN_PRESS_GAP_MS = 1200; // pause between captures so the user can re-aim/re-press
    // Percentage tolerance (0.20 = 20%) for comparing raw-timing captures - see Pronto.approxEqual
    // javadoc. Not calibrated against real hardware jitter; override per-call with --ef tolerance N.
    private static final double DEFAULT_LEARN_TOLERANCE = 0.30;
    private volatile double learnTolerance = DEFAULT_LEARN_TOLERANCE;
    private SeekBar tolBar;

    private void cmdLearn(ProgramButton btn, int presses, int timeoutMs) {
        cmdLearn(btn, presses, timeoutMs, learnTolerance);
    }

    private void cmdLearn(ProgramButton btn, int presses, int timeoutMs, double tolerance) {
        if (presses < 1) throw new IllegalArgumentException("presses must be >= 1");
        synchronized (this) {
            if (learningInProgress != null) {
                say("Already learning " + learningInProgress + " - wait for it to finish (or time out) "
                        + "before starting another.");
                return;
            }
            learningInProgress = btn;
        }
        setDiagramLearning(btn);
        if (presses == 1) {
            say("Learning " + btn + " (unverified - single press, no pattern check). "
                    + "Point the original remote at the Fire TV's IR receiver and press the button NOW.");
        } else {
            say("Learning " + btn + ": you'll be asked to press the SAME button on the original remote "
                    + presses + " times, " + (LEARN_PRESS_GAP_MS / 1000.0) + "s apart (tolerance "
                    + Math.round(tolerance * 100) + "%). Point it at the Fire TV's IR receiver.");
        }
        learnOnePress(btn, presses, timeoutMs, tolerance, 1, new ArrayList<String>());
    }

    /** Call at EVERY terminal point of a learn sequence (success, abort, timeout, error) - clears both
     *  the re-entry guard and the diagram's red pulsing state. Missing this on any exit path leaves
     *  the diagram stuck red and blocks all further learning (learningInProgress never clears). */
    private void endLearning() {
        learningInProgress = null;
        setDiagramLearning(null);
        refreshDiagram();
        refocusDiagram();
    }

    /** Captures press `n` of `total`, then recurses for the next one (or finishes on the last). */
    private void learnOnePress(final ProgramButton btn, final int total, final int timeoutMs, final double tolerance,
                                final int n, final ArrayList<String> captured) {
        if (destroyed || learningInProgress != btn) return;
        say("Press " + n + "/" + total + " now" + (n == 1 ? "." : " - the SAME button again."));
        try {
            activeLearnToken = dcs.startIrLearning(timeoutMs, new DcsClient.LearnListener() {
            @Override public void onLearned(String pronto) {
                activeLearnToken = null;
                if (pronto == null) {
                    say("Press " + n + "/" + total + " returned an empty code. Aborting - please retry from the start.");
                    endLearning();
                    return;
                }
                String norm = Pronto.normalize(pronto);
                String err = Pronto.check(norm);
                if (err != null) {
                    say("Press " + n + "/" + total + " failed validation (" + err + "). Aborting - please retry from the start.");
                    endLearning();
                    return;
                }
                captured.add(norm);
                say("Got press " + n + "/" + total + ".");
                if (n < total) {
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            worker.submit(new Runnable() {
                                @Override public void run() { learnOnePress(btn, total, timeoutMs, tolerance, n + 1, captured); }
                            });
                        }
                    }, LEARN_PRESS_GAP_MS);
                } else {
                    finishLearn(btn, captured, tolerance);
                }
            }
            @Override public void onTimeout() {
                activeLearnToken = null;
                say("Press " + n + "/" + total + " timed out after " + timeoutMs + "ms with no code received. "
                        + "Aborting - please retry from the start.");
                endLearning();
            }
            @Override public void onError(String message) {
                activeLearnToken = null;
                say("Press " + n + "/" + total + " error: " + message + ". Aborting - please retry from the start.");
                endLearning();
            }
            });
        } catch (RemoteException | RuntimeException e) {
            say("Press " + n + "/" + total + ": failed to start learning: " + e + ". Aborting - please retry from the start.");
            endLearning();
        }
    }

    /**
     * Validates the captured presses against the only two patterns a real remote can produce:
     *  - all identical           -> simple (non-toggle) protocol: store as code1 only.
     *  - alternating A,B,A,B,... -> toggle-bit protocol (RC5/RC6-style): store as code1=A, code2=B.
     * Anything else means at least one press almost certainly wasn't captured cleanly (misaim, double
     * press, a different button, interference) - NOT a legitimate third protocol state, since IR toggle
     * protocols only have two alternating states. We refuse to store a code rather than guess.
     *
     * Comparison is tolerance-based (Pronto.approxEqual), not exact string equality - raw IR capture
     * has real press-to-press timing jitter even for genuinely identical protocol states, so exact
     * match was rejecting valid codes in practice. See Pronto.approxEqual's javadoc for exactly what's
     * compared strictly (structure) vs with tolerance (timing values).
     */
    private void finishLearn(ProgramButton btn, ArrayList<String> captured, double tolerance) {
        String a = captured.get(0);
        boolean allSame = true;
        for (String c : captured) if (!Pronto.approxEqual(c, a, tolerance)) { allSame = false; break; }

        if (allSame) {
            putCode(btn, 1, a);
            putCode(btn, 2, null);
            say("Learned " + btn + " - all " + captured.size() + " presses matched within tolerance (simple protocol):\n  " + a);
            say(IrDetector.describe(a));
            say("Run 'push' when you've learned everything you want.");
            endLearning();
            return;
        }

        String b = captured.get(1);
        boolean alternates = true;
        for (int i = 0; i < captured.size(); i++) {
            String expected = (i % 2 == 0) ? a : b;
            if (!Pronto.approxEqual(captured.get(i), expected, tolerance)) { alternates = false; break; }
        }

        if (alternates) {
            putCode(btn, 1, a);
            putCode(btn, 2, b);
            say("Learned " + btn + " - presses alternated A/B within tolerance as expected (toggle-bit protocol):\n"
                    + "  code1: " + a + "\n  code2: " + b);
            say("code1 " + IrDetector.describe(a));
            say("code2 " + IrDetector.describe(b));
            say("Run 'push' when you've learned everything you want.");
            endLearning();
            return;
        }

        StringBuilder sb = new StringBuilder("Learning " + btn + " FAILED validation: the " + captured.size()
                + " presses didn't settle into a consistent pattern (all-identical, or a clean A/B alternation)"
                + " even within " + Math.round(tolerance * 100) + "% tolerance.\n"
                + "This usually means at least one press wasn't captured cleanly - retry, or if this keeps "
                + "happening for codes you're confident are fine, try a looser tolerance, e.g.:\n"
                + "  adb shell am start -n " + getPackageName() + "/.CommandActivity --es cmd learn --es fn "
                + btn.name() + " --ef tolerance 0.35\n");
        for (int i = 0; i < captured.size(); i++) {
            String c = captured.get(i);
            int words = c.split(" ").length;
            sb.append("  press ").append(i + 1).append(" (").append(words).append(" words): ").append(c).append('\n');
        }
        say(sb.toString());
        endLearning();
    }

    private void cmdSet(ProgramButton btn, String code1, String code2) {
        if (code1 == null) throw new IllegalArgumentException("Missing --es code1 '<pronto hex>'");
        String err = Pronto.check(code1);
        if (err != null) throw new IllegalArgumentException("code1 invalid: " + err);
        putCode(btn, 1, Pronto.normalize(code1));
        if (code2 != null && code2.trim().length() > 0) {
            err = Pronto.check(code2);
            if (err != null) throw new IllegalArgumentException("code2 invalid: " + err);
            putCode(btn, 2, Pronto.normalize(code2));
        } else {
            putCode(btn, 2, null);
        }
        say("Stored " + btn + (code2 != null ? " (code1 + code2)" : " (code1)") + ". Run 'push' to send.");
        refreshDiagram();
    }

    private void cmdShow() {
        StringBuilder sb = new StringBuilder("Stored codes:\n");
        for (ProgramButton btn : ProgramButton.values()) {
            String c1 = getCode(btn, 1), c2 = getCode(btn, 2);
            if (c1 == null) continue; // only list buttons that actually have something stored
            sb.append("  ").append(btn).append(" -> ").append(ButtonIds.ID.get(btn)).append(":\n");
            sb.append("    code1: ").append(c1).append('\n');
            if (c2 != null) sb.append("    code2: ").append(c2).append('\n');
        }
        if (sb.length() <= "Stored codes:\n".length()) sb.append("  (none)\n");
        say(sb.toString());
    }

    private BleTransfer.Listener bluetoothLog() {
        return new BleTransfer.Listener() {
            public void progress(String s) { say(s); }
            public void finished(boolean success, String s) { say((success ? "SUCCESS: " : "ERROR: ") + s); }
        };
    }

    private void clearPushedState() {
        SharedPreferences.Editor edit = store.edit();
        for (ProgramButton button : ProgramButton.values()) edit.putBoolean(button.name() + ".pushed", false);
        edit.apply(); refreshDiagram();
    }

    private void cmdPush(String address) throws RemoteException {
        if (destroyed) return;
        final java.util.Map<ProgramButton, String[]> snapshot = new java.util.EnumMap<>(ProgramButton.class);
        for (ProgramButton button : ProgramButton.values()) {
            String first = getCode(button, 1);
            if (first != null) snapshot.put(button, new String[]{first, getCode(button, 2)});
        }
        byte[] table = IrTable.build(snapshot);
        ensureDcSettingsEnabled();
        say("Copying " + snapshot.size() + " buttons over Bluetooth. This replaces the remote's custom IR table."
                + " Keep the remote awake and in range.");
        clearPushedState();
        dcs.bluetooth(address, table, new BleTransfer.Listener() {
            public void progress(String message) { say(message); }
            public void finished(boolean success, String message) {
                say((success ? "SUCCESS: " : "ERROR: ") + message);
                if (success) {
                    for (java.util.Map.Entry<ProgramButton, String[]> entry : snapshot.entrySet()) {
                        ProgramButton button = entry.getKey(); String[] pair = entry.getValue();
                        // A local edit during transfer must not be marked as copied.
                        setPushed(button, java.util.Objects.equals(pair[0], getCode(button, 1))
                                && java.util.Objects.equals(pair[1], getCode(button, 2)));
                    }
                    refreshDiagram();
                }
            }
        });
    }

    private void cmdClearRemote(String address) throws RemoteException {
        if (destroyed) return;
        byte[] table = IrTable.buildEmpty();
        ensureDcSettingsEnabled();
        say("Clearing the remote's custom IR table over Bluetooth. Keep the remote awake and in range.");
        clearPushedState();
        dcs.bluetooth(address, table, new BleTransfer.Listener() {
            public void progress(String message) { say(message); }
            public void finished(boolean success, String message) {
                say((success ? "SUCCESS: " : "ERROR: ") + message);
                if (success) {
                    for (ProgramButton btn : ProgramButton.values()) { putCode(btn, 1, null); putCode(btn, 2, null); }
                    refreshDiagram();
                    refocusDiagram();
                }
            }
        });
    }
    
    // The setting write must happen in the root process, just like the protected service calls.
    private void ensureDcSettingsEnabled() throws RemoteException {
        int old = dcs.ensureRemoteSetting();
        if (old != 1) say("Enabled show_dc_settings through root (previous value " + old + ").");
    }

    // ------------------------------------------------------------------ storage

    private String getCode(ProgramButton btn, int slot) { return store.getString(btn.name() + "." + slot, null); }

    // Tracks whether the CURRENTLY STORED code for a button has actually been copied to the physical
    // remote yet (blue = learned/set locally, not pushed; green = pushed). Any change to the stored
    // code (learn, set, clear) invalidates this - the remote's real state no longer matches local
    // storage until the next successful push.
    private boolean isPushed(ProgramButton btn) { return store.getBoolean(btn.name() + ".pushed", false); }

    private void setPushed(ProgramButton btn, boolean pushed) {
        store.edit().putBoolean(btn.name() + ".pushed", pushed).apply();
    }

    private void putCode(ProgramButton btn, int slot, String code) {
        SharedPreferences.Editor e = store.edit();
        if (code == null) e.remove(btn.name() + "." + slot); else e.putString(btn.name() + "." + slot, code);
        e.putBoolean(btn.name() + ".pushed", false); // any code change invalidates "matches the remote"
        e.apply();
    }
}
