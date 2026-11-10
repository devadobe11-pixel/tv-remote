package com.tvremote.adb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String PREFS = "tv_remote_prefs";
    private static final String KEY_IP = "last_ip";
    private static final String KEY_OCTET = "last_octet";
    static final long STALE_KEY_TAP_MS = 1500L;

    private static final int REQ_VOICE_CMD = 101;
    private static final int REQ_VOICE_TYPE = 102;

    private final AdbClient adb = new AdbClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    private SharedPreferences prefs;
    private TextView tvStatus;
    private TextView tvVoiceResult;
    private EditText etTextInput;
    private volatile long lastScanMs = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        adb.setAssetManager(getAssets());

        tvStatus = findViewById(R.id.tvStatus);
        tvVoiceResult = findViewById(R.id.tvVoiceResult);
        etTextInput = findViewById(R.id.etTextInput);

        bindUi();
        autoConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!adb.isConnected()) {
            autoConnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        adb.closeQuietly();
    }

    private void bindUi() {
        findViewById(R.id.btnFindTv).setOnClickListener(v -> {
            haptic(v);
            autoConnect();
        });
        findViewById(R.id.btnSetIp).setOnClickListener(v -> {
            haptic(v);
            promptManualIp();
        });

        findViewById(R.id.btnVoiceCmd).setOnClickListener(v -> {
            haptic(v);
            startVoiceInput(REQ_VOICE_CMD, "Say movie/song name, \"Open Netflix\", or \"Volume up\"");
        });
        findViewById(R.id.btnMicType).setOnClickListener(v -> {
            haptic(v);
            startVoiceInput(REQ_VOICE_TYPE, "Speak text for TV typing pad");
        });

        // Quick Apps
        bindShell(R.id.btnAppYoutube, "Opening SmartTube", VoiceCommandParser.CMD_OPEN_YOUTUBE);
        bindShell(R.id.btnAppNetflix, "Opening Netflix", VoiceCommandParser.CMD_OPEN_NETFLIX);
        bindShell(R.id.btnAppPrime, "Opening Prime Video", VoiceCommandParser.CMD_OPEN_PRIME);
        bindShell(R.id.btnAppHotstar, "Opening JioHotstar", VoiceCommandParser.CMD_OPEN_HOTSTAR);
        bindShell(R.id.btnAppInputs, "Opening Inputs", VoiceCommandParser.CMD_OPEN_INPUTS);
        bindShell(R.id.btnAppCast, "Opening Miracast", VoiceCommandParser.CMD_OPEN_MIRACAST);

        // Digital Remote Keys
        bindKey(R.id.btnPower, 26);
        bindKey(R.id.btnHome, 3);
        bindKey(R.id.btnBack, 4);
        bindKey(R.id.btnUp, 19);
        bindKey(R.id.btnDown, 20);
        bindKey(R.id.btnLeft, 21);
        bindKey(R.id.btnRight, 22);
        bindKey(R.id.btnOk, 23);
        bindKey(R.id.btnPlayPause, 85);
        bindKey(R.id.btnVolDown, 25);
        bindKey(R.id.btnMute, 164);
        bindKey(R.id.btnVolUp, 24);
        bindKey(R.id.btnKeyDel, 67);
        bindKey(R.id.btnKeyEnter, 66);

        // Typing Pad
        findViewById(R.id.btnSendType).setOnClickListener(v -> {
            haptic(v);
            submitTypedText(false);
        });
        findViewById(R.id.btnSendSearch).setOnClickListener(v -> {
            haptic(v);
            submitTypedText(true);
        });
        etTextInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitTypedText(true);
                return true;
            }
            return false;
        });
    }

    private void bindKey(int viewId, int keyCode) {
        findViewById(viewId).setOnClickListener(v -> {
            haptic(v);
            long enqueuedAt = System.currentTimeMillis();
            runTvTask(null, true, enqueuedAt, () -> adb.sendKeys(keyCode));
        });
    }

    private void bindShell(int viewId, String label, String cmd) {
        findViewById(viewId).setOnClickListener(v -> {
            haptic(v);
            runTvTask(label, false, System.currentTimeMillis(), () -> adb.shell(cmd));
        });
    }

    private void submitTypedText(boolean pressEnter) {
        String text = etTextInput.getText().toString();
        if (text.trim().isEmpty()) {
            if (pressEnter) {
                runTvTask("Enter", true, System.currentTimeMillis(), () -> adb.sendKeys(66));
            }
            return;
        }
        etTextInput.setText("");
        if (pressEnter) {
            runTvTask(null, false, System.currentTimeMillis(), () -> performAppAwareSearch(text.trim(), false));
        } else {
            runTvTask("Typed: " + text.trim(), false, System.currentTimeMillis(), () -> adb.sendText(text, false));
        }
    }

    private void startVoiceInput(int reqCode, String prompt) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        try {
            startActivityForResult(intent, reqCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No speech recognizer found on phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (matches == null || matches.isEmpty()) return;
        String spoken = matches.get(0);

        if (requestCode == REQ_VOICE_TYPE) {
            etTextInput.setText(spoken);
            etTextInput.setSelection(spoken.length());
            tvVoiceResult.setText("Dictated: \"" + spoken + "\" — tap Type Only or Search ↵");
            return;
        }

        if (requestCode == REQ_VOICE_CMD) {
            VoiceCommandParser.ParsedCommand cmd = VoiceCommandParser.parse(spoken);
            tvVoiceResult.setText("Heard: \"" + spoken + "\" → " + cmd.label);
            long now = System.currentTimeMillis();
            switch (cmd.type) {
                case SHELL:
                    runTvTask(cmd.label, false, now, () -> adb.shell(cmd.payload));
                    break;
                case KEYS:
                    runTvTask(cmd.label, false, now, () -> adb.sendKeys(cmd.keys));
                    break;
                case TEXT_TYPE:
                    runTvTask(cmd.label, false, now, () -> adb.sendText(cmd.payload, false));
                    break;
                case SEARCH_YOUTUBE:
                    runTvTask("YouTube Search: " + cmd.payload, false, now,
                            () -> adb.shell(VoiceCommandParser.smartTubeSearchCmd(cmd.payload)));
                    break;
                case SEARCH_PRIME:
                    runTvTask("Prime Search: " + cmd.payload, false, now,
                            () -> adb.shell(VoiceCommandParser.primeSearchCmd(cmd.payload)));
                    break;
                case SEARCH_NETFLIX:
                    runTvTask(null, false, now, () -> searchNetflix(cmd.payload));
                    break;
                case SMART_SEARCH:
                    runTvTask(null, false, now, () -> performAppAwareSearch(cmd.payload, false));
                    break;
                case QUERY_OR_DRAFT:
                    runTvTask(null, false, now, () -> performAppAwareSearch(cmd.payload, true));
                    break;
            }
        }
    }

    private void performAppAwareSearch(String query, boolean draftIfOnHome) throws Exception {
        String focus = adb.getForegroundWindow();
        if (focus.contains("app.smarttube")) {
            adb.shell(VoiceCommandParser.smartTubeSearchCmd(query));
            ui.post(() -> tvVoiceResult.setText("YouTube Search: " + query));
            return;
        }
        if (focus.contains("com.amazon.amazonvideo.livingroom")) {
            adb.shell(VoiceCommandParser.primeSearchCmd(query));
            ui.post(() -> tvVoiceResult.setText("Prime Video Search: " + query));
            return;
        }
        if (focus.contains("com.netflix.mediaclient")) {
            searchNetflix(query);
            return;
        }
        if (draftIfOnHome) {
            ui.post(() -> {
                etTextInput.setText(query);
                etTextInput.setSelection(query.length());
                tvVoiceResult.setText("Open YouTube/Prime/Netflix to voice search, or tap Search ↵ for \"" + query + "\"");
            });
            return;
        }
        adb.sendText(query, true);
        ui.post(() -> tvVoiceResult.setText("Search: " + query));
    }

    private void searchNetflix(String query) throws Exception {
        ui.post(() -> tvVoiceResult.setText("Searching Netflix: " + query + "..."));
        String focus = adb.getForegroundWindow();
        if (focus.contains("com.netflix.mediaclient/o.kxL")) {
            // Exit existing Netflix search activity so /search opens a clean empty search box.
            adb.sendKeys(4);
            Thread.sleep(350);
            if (adb.getForegroundWindow().contains("com.netflix.mediaclient/o.kxL")) {
                adb.sendKeys(4);
                Thread.sleep(350);
            }
        }
        adb.shell(VoiceCommandParser.netflixOpenSearchCmd());

        // Wait until Netflix search activity gains focus (up to 6s).
        long deadline = System.currentTimeMillis() + 6000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            if (adb.getForegroundWindow().contains("com.netflix.mediaclient/o.kxL")) {
                Thread.sleep(350);
                break;
            }
        }
        adb.sendText(query, true);
        ui.post(() -> tvVoiceResult.setText("Netflix Search: " + query));
    }

    private void autoConnect() {
        // Ignore repeat Auto Find taps while a subnet scan is already running.
        if (!isScanning.compareAndSet(false, true)) {
            return;
        }
        setStatus("Scanning network for TV...", 0xFFFBBF24);
        String savedIp = prefs.getString(KEY_IP, "");
        int savedOctet = prefs.getInt(KEY_OCTET, -1);
        lastScanMs = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                String found = TvDiscovery.findTvIp(savedIp, savedOctet);
                if (found == null) {
                    ui.post(() -> setStatus("TV not found (check Hotspot + TV ADB 5555)", 0xFFF87171));
                    return;
                }
                connectToIpSync(found);
            } finally {
                isScanning.set(false);
            }
        });
    }

    private void connectToIpSync(String ip) {
        try {
            adb.connect(ip);
            int octet = TvDiscovery.parseLastOctet(ip);
            SharedPreferences.Editor ed = prefs.edit().putString(KEY_IP, ip);
            if (octet > 0) ed.putInt(KEY_OCTET, octet);
            ed.apply();
            ui.post(() -> setStatus("● Connected: " + ip + ":5555", 0xFF34D399));
        } catch (Exception e) {
            ui.post(() -> setStatus("Connect failed (" + ip + "): " + e.getMessage(), 0xFFF87171));
        }
    }

    static boolean isStaleTap(long enqueuedAtMs, long nowMs) {
        return nowMs - enqueuedAtMs > STALE_KEY_TAP_MS;
    }

    private void runTvTask(String label, boolean dropIfStale, long enqueuedAtMs, TvAction action) {
        worker.execute(() -> {
            // Drop stale D-pad taps queued behind an offline timeout or slow search wait.
            if (dropIfStale && isStaleTap(enqueuedAtMs, System.currentTimeMillis())) {
                return;
            }
            if (!adb.isConnected()) {
                if (dropIfStale) {
                    ui.post(() -> setStatus("TV offline — tap Auto Find", 0xFFF87171));
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastScanMs < 4000L) {
                    ui.post(() -> setStatus("TV offline — tap Auto Find", 0xFFF87171));
                    return;
                }
                lastScanMs = now;
                String found = TvDiscovery.findTvIp(prefs.getString(KEY_IP, ""), prefs.getInt(KEY_OCTET, -1));
                if (found == null) {
                    ui.post(() -> setStatus("TV offline — check hotspot & ADB", 0xFFF87171));
                    return;
                }
                connectToIpSync(found);
                if (!adb.isConnected()) return;
            }
            try {
                action.run();
                if (label != null) {
                    ui.post(() -> tvVoiceResult.setText(label));
                }
            } catch (Exception e) {
                ui.post(() -> setStatus("Error: " + e.getMessage(), 0xFFF87171));
            }
        });
    }

    private void promptManualIp() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("e.g. 192.168.43.10");
        input.setText(prefs.getString(KEY_IP, ""));
        new AlertDialog.Builder(this)
                .setTitle("TV IP Address (ADB port 5555)")
                .setView(input)
                .setPositiveButton("Connect", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        setStatus("Connecting to " + ip + "...", 0xFFFBBF24);
                        worker.execute(() -> connectToIpSync(ip));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setStatus(String text, int color) {
        tvStatus.setText(text);
        tvStatus.setTextColor(color);
    }

    private void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    @FunctionalInterface
    private interface TvAction {
        void run() throws Exception;
    }
}
