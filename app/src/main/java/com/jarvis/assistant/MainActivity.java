package com.jarvis.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int REQ_PERMS = 1, REQ_MIC = 2, REQ_VOICE_UI = 3;
    private static final int CYAN = Color.rgb(63, 240, 255), BG = Color.rgb(2, 6, 13),
            PANEL = Color.rgb(7, 19, 31), DIM = Color.rgb(107, 143, 163), WARN = Color.rgb(255, 179, 71);

    private SharedPreferences prefs;
    private Brain brain;
    private Actions actions;

    private LinearLayout log;
    private ScrollView scroll;
    private OrbView orb;
    private TextView status, hint;
    private EditText input;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private boolean listening = false, busy = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // --------------------------------------------------------------- life
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        brain = new Brain(prefs);
        actions = new Actions(this, prefs);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        tts = new TextToSpeech(this, this);

        String name = prefs.getString("name", "");
        addMsg("Namaste" + (name.isEmpty() ? "" : " " + name) + "! Main JARVIS hoon. Orb dabao aur bolo.", 1);
        if (!brain.hasKey()) addMsg("Tip: ⚙ Settings mein free Gemini key daalo — tab main kuch bhi baat kar paunga.", 2);

        askPermissions();
        if (isAssistIntent(getIntent())) ui.postDelayed(this::listen, 900);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isAssistIntent(intent)) ui.postDelayed(this::listen, 400);
    }

    private boolean isAssistIntent(Intent i) {
        if (i == null || i.getAction() == null) return false;
        return i.getAction().equals(Intent.ACTION_ASSIST) || i.getAction().equals(Intent.ACTION_VOICE_COMMAND);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listening && recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            listening = false;
            setState(OrbView.IDLE);
        }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) {} }
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} }
        bg.shutdownNow();
        super.onDestroy();
    }

    private void askPermissions() {
        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS};
        ArrayList<String> need = new ArrayList<>();
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
            else addMsg("Mic ki permission ke bina main sun nahi sakta. Phone Settings → Apps → JARVIS → Permissions → Microphone → Allow.", 2);
        }
    }

    // ----------------------------------------------------------------- UI
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(14), dp(14), dp(4));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("J.A.R.V.I.S");
        title.setTextColor(CYAN);
        title.setTextSize(22);
        title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        title.setLetterSpacing(0.25f);
        title.setShadowLayer(18, 0, 0, CYAN);
        status = new TextView(this);
        status.setText("ONLINE");
        status.setTextColor(DIM);
        status.setTextSize(12);
        status.setLetterSpacing(0.1f);
        titles.addView(title);
        titles.addView(status);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button gear = new Button(this);
        gear.setText("⚙");
        gear.setTextSize(20);
        gear.setTextColor(CYAN);
        gear.setBackground(box(Color.TRANSPARENT, Color.rgb(29, 58, 77), 10));
        gear.setOnClickListener(v -> openSettings());
        head.addView(gear, new LinearLayout.LayoutParams(dp(52), dp(46)));
        root.addView(head);

        // orb
        orb = new OrbView(this);
        orb.setContentDescription("Bolne ke liye dabao");
        orb.setOnClickListener(v -> listen());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(230), dp(230));
        olp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(orb, olp);

        hint = new TextView(this);
        hint.setText("Orb dabao aur bolo — \"YouTube pe Arijit Singh chalao\"");
        hint.setTextColor(DIM);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(20), 0, dp(20), dp(6));
        root.addView(hint);

        // chat log
        scroll = new ScrollView(this);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        log.setPadding(dp(12), dp(6), dp(12), dp(6));
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // input bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(12));
        input = new EditText(this);
        input.setHint("Ya yahan type karo...");
        input.setHintTextColor(DIM);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(box(PANEL, Color.rgb(29, 58, 77), 12));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendTyped();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button send = new Button(this);
        send.setText("SEND");
        send.setTextColor(BG);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setBackground(box(CYAN, 0, 12));
        send.setOnClickListener(v -> sendTyped());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(78), dp(48));
        slp.leftMargin = dp(8);
        bar.addView(send, slp);
        root.addView(bar);

        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void sendTyped() {
        String t = input.getText().toString().trim();
        if (!t.isEmpty()) { input.setText(""); handle(t); }
    }

    /** who: 0 = me, 1 = JARVIS, 2 = system note */
    private void addMsg(String text, int who) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(who == 2 ? 13 : 16);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        lp.bottomMargin = dp(5);
        if (who == 2) {
            tv.setTextColor(WARN);
            tv.setGravity(Gravity.CENTER);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            tv.setPadding(dp(8), dp(2), dp(8), dp(2));
        } else {
            tv.setTextColor(who == 0 ? Color.WHITE : Color.rgb(191, 246, 255));
            tv.setBackground(who == 0 ? box(Color.rgb(14, 42, 61), Color.rgb(29, 74, 99), 14)
                    : box(PANEL, Color.rgb(19, 64, 90), 14));
            tv.setPadding(dp(13), dp(10), dp(13), dp(10));
            tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82));
            lp.gravity = who == 0 ? Gravity.END : Gravity.START;
        }
        log.addView(tv, lp);
        while (log.getChildCount() > 120) log.removeViewAt(0);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setState(int s) {
        orb.setState(s);
        status.setText(s == OrbView.LISTENING ? "SUN RAHA HOON..." : s == OrbView.THINKING ? "SOCH RAHA HOON..."
                : s == OrbView.SPEAKING ? "BOL RAHA HOON..." : "ONLINE");
    }

    // ---------------------------------------------------------- listening
    private String lang() { return prefs.getString("lang", "hi-IN"); }

    private Intent recognizerIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang());
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "JARVIS sun raha hai...");
        return i;
    }

    private void listen() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (listening) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
            return;
        }
        if (tts != null) tts.stop();

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Fallback: Google's own voice popup
            try {
                startActivityForResult(recognizerIntent(), REQ_VOICE_UI);
            } catch (ActivityNotFoundException e) {
                addMsg("Phone mein Google voice engine nahi mila. Play Store se \"Google\" app install/update karo. Tab tak type kar sakte ho.", 2);
            }
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        try {
            recognizer.startListening(recognizerIntent());
            listening = true;
            setState(OrbView.LISTENING);
            hint.setText("Bolo...");
        } catch (Exception e) {
            listening = false;
            resetRecognizer();
            addMsg("Mic shuru nahi hua, phir se dabao.", 2);
        }
    }

    private void resetRecognizer() {
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) {} }
        recognizer = null;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { hint.setText("Bolo..."); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rms) { orb.setLevel(rms); }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { setState(OrbView.THINKING); }

        @Override public void onError(int error) {
            listening = false;
            setState(OrbView.IDLE);
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    hint.setText("Kuch suna nahi — phir se orb dabao."); break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    hint.setText("Internet slow hai — phir try karo."); break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    addMsg("Mic permission do: Settings → Apps → JARVIS → Permissions.", 2); break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    resetRecognizer();
                    hint.setText("Ek second... phir se dabao."); break;
                default:
                    resetRecognizer();
                    hint.setText("Voice error (" + error + ") — phir se dabao.");
            }
        }

        @Override public void onResults(Bundle results) {
            listening = false;
            setState(OrbView.IDLE);
            ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            hint.setText("Orb dabao aur bolo");
            if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) handle(r.get(0).trim());
        }

        @Override public void onPartialResults(Bundle partial) {
            ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) hint.setText(r.get(0));
        }

        @Override public void onEvent(int eventType, Bundle params) {}
    };

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE_UI && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) handle(r.get(0));
        }
    }

    // ------------------------------------------------------------ thinking
    private void handle(String text) {
        if (busy) return;
        busy = true;
        addMsg(text, 0);
        setState(OrbView.THINKING);
        bg.execute(() -> {
            Brain.Result r;
            String err = null;
            try {
                r = brain.think(text);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                r = brain.local(text);
            }
            final Brain.Result fr = r;
            final String ferr = err;
            ui.post(() -> respond(text, fr, ferr));
        });
    }

    private void respond(String text, Brain.Result r, String err) {
        setState(OrbView.IDLE);
        busy = false;
        if (err != null) addMsg("AI se connect nahi hua: " + err + "\n(Internet / key check karo. Abhi basic mode chal raha hai.)", 2);
        String override = actions.run(r.action);
        String reply = override != null ? override : (r.reply == null || r.reply.isEmpty() ? "Ho gaya." : r.reply);
        addMsg(reply, 1);
        brain.remember(text, reply, r.action);
        speak(reply);
    }

    // ------------------------------------------------------------ speaking
    @Override
    public void onInit(int st) {
        if (st != TextToSpeech.SUCCESS) {
            ui.post(() -> addMsg("Bolne wala engine (TTS) nahi mila. Play Store se \"Speech Services by Google\" install karo.", 2));
            return;
        }
        ttsReady = true;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { ui.post(() -> setState(OrbView.SPEAKING)); }
            @Override public void onDone(String id) {
                ui.post(() -> {
                    setState(OrbView.IDLE);
                    if (prefs.getBoolean("auto", false) && !actions.leftApp && hasWindowFocus()) ui.postDelayed(() -> listen(), 250);
                });
            }
            @Override public void onError(String id) { ui.post(() -> setState(OrbView.IDLE)); }
        });
    }

    private void speak(String text) {
        if (!ttsReady || !prefs.getBoolean("speak", true)) return;
        String reply = prefs.getString("reply", "hinglish");
        boolean deva = text.matches("(?s).*[\\u0900-\\u097F].*");
        Locale loc = (deva || reply.equals("hindi")) ? new Locale("hi", "IN") : new Locale("en", "IN");
        int ok = tts.setLanguage(loc);
        if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.ENGLISH);
        tts.setPitch(0.9f);
        tts.setSpeechRate(1.0f);
        String clean = text.replaceAll("[*_#>" + (char) 96 + "]", "").replaceAll("https?://\\S+", "link");
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "j" + System.currentTimeMillis());
    }

    // ------------------------------------------------------------ settings
    private void openSettings() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText name = field(f, "Aapka naam", prefs.getString("name", ""), false);
        EditText key = field(f, "Gemini API key (free) — aistudio.google.com/apikey", prefs.getString("key", ""), true);
        EditText model = field(f, "AI model (khaali = default)", prefs.getString("model", ""), false);

        Spinner lang = spinner(f, "Sunne ki bhasha", new String[]{"Hindi / Hinglish", "English (India)", "English (US)"},
                new String[]{"hi-IN", "en-IN", "en-US"}, lang());
        Spinner reply = spinner(f, "Jawab ki bhasha", new String[]{"Hinglish", "Hindi (देवनागरी)", "English"},
                new String[]{"hinglish", "hindi", "english"}, prefs.getString("reply", "hinglish"));

        CheckBox speak = check(f, "JARVIS bol ke jawab de", prefs.getBoolean("speak", true));
        CheckBox auto = check(f, "Jawab ke baad phir se sune (conversation mode)", prefs.getBoolean("auto", false));

        ScrollView sv = new ScrollView(this);
        sv.addView(f);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("JARVIS Settings")
                .setView(sv)
                .setPositiveButton("SAVE", (d, w) -> {
                    prefs.edit()
                            .putString("name", name.getText().toString().trim())
                            .putString("key", key.getText().toString().trim())
                            .putString("model", model.getText().toString().trim())
                            .putString("lang", ((String[]) lang.getTag())[lang.getSelectedItemPosition()])
                            .putString("reply", ((String[]) reply.getTag())[reply.getSelectedItemPosition()])
                            .putBoolean("speak", speak.isChecked())
                            .putBoolean("auto", auto.isChecked())
                            .apply();
                    addMsg("Settings save ✔" + (brain.hasKey() ? "  AI dimaag ON." : "  (Key nahi — basic mode)"), 2);
                })
                .setNeutralButton("CHAT CLEAR", (d, w) -> { brain.clearHistory(); log.removeAllViews(); })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private TextView label(LinearLayout f, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(13);
        t.setPadding(0, dp(12), 0, dp(2));
        f.addView(t);
        return t;
    }

    private EditText field(LinearLayout f, String lbl, String value, boolean secret) {
        label(f, lbl);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        f.addView(e);
        return e;
    }

    private Spinner spinner(LinearLayout f, String lbl, String[] labels, String[] values, String current) {
        label(f, lbl);
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        s.setAdapter(ad);
        s.setTag(values);
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) s.setSelection(i);
        f.addView(s);
        return s;
    }

    private CheckBox check(LinearLayout f, String lbl, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(lbl);
        c.setChecked(on);
        c.setTextColor(Color.WHITE);
        c.setPadding(0, dp(6), 0, dp(6));
        f.addView(c);
        return c;
    }
}
