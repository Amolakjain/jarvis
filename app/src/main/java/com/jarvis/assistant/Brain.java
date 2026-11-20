package com.jarvis.assistant;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JARVIS's thinking: Gemini AI when a key is set, simple offline rules otherwise. */
public class Brain {

    public static class Result {
        public String reply = "";
        public JSONObject action = null;
    }

    private final SharedPreferences prefs;

    public Brain(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public boolean hasKey() {
        return !prefs.getString("key", "").trim().isEmpty();
    }

    // ------------------------------------------------------------------ AI
    public Result think(String text) throws Exception {
        if (!hasKey()) return local(text);
        String model = prefs.getString("model", "").trim();
        if (model.isEmpty()) model = "gemini-flash-latest";
        try {
            return callGemini(model, text);
        } catch (ModelNotFound e) {
            // Model names change over time; fall back to a known one automatically.
            return callGemini("gemini-2.5-flash", text);
        }
    }

    private static class ModelNotFound extends Exception {
        ModelNotFound(String m) { super(m); }
    }

    private Result callGemini(String model, String text) throws Exception {
        String key = prefs.getString("key", "").trim();
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPrompt()))));

        JSONArray contents = new JSONArray();
        JSONArray hist = history();
        int start = Math.max(0, hist.length() - 16);
        for (int i = start; i < hist.length(); i++) {
            JSONObject h = hist.getJSONObject(i);
            contents.put(new JSONObject().put("role", h.getString("role"))
                    .put("parts", new JSONArray().put(new JSONObject().put("text", h.getString("text")))));
        }
        contents.put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", text))));
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.7)
                .put("maxOutputTokens", 1024));

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + URLEncoder.encode(model, "UTF-8") + ":generateContent?key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        String resp = read(code >= 400 ? con.getErrorStream() : con.getInputStream());
        con.disconnect();

        if (code == 404) throw new ModelNotFound(resp);
        if (code >= 400) {
            String msg = "HTTP " + code;
            try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) {}
            if (code == 400 && msg.toLowerCase(Locale.ROOT).contains("api key")) msg = "API key galat hai — Settings mein dobara paste karo.";
            if (code == 429) msg = "Free limit thodi der ke liye khatam — 1 minute baad try karo.";
            throw new Exception(msg);
        }

        JSONObject data = new JSONObject(resp);
        StringBuilder raw = new StringBuilder();
        JSONArray parts = data.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
        for (int i = 0; i < parts.length(); i++) raw.append(parts.getJSONObject(i).optString("text", ""));

        Result r = new Result();
        String fence = "" + (char) 96 + (char) 96 + (char) 96;
        String s = raw.toString().trim();
        if (s.startsWith(fence)) s = s.substring(3).replaceFirst("^json", "");
        if (s.endsWith(fence)) s = s.substring(0, s.length() - 3);
        s = s.trim();
        try {
            JSONObject j = new JSONObject(s);
            r.reply = j.optString("reply", "");
            r.action = j.optJSONObject("action");
        } catch (Exception e) {
            r.reply = s;
        }
        return r;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private String systemPrompt() {
        String name = prefs.getString("name", "");
        String reply = prefs.getString("reply", "hinglish");
        String lang = reply.equals("hindi") ? "Hindi in Devanagari script"
                : reply.equals("english") ? "English"
                : "Hinglish (Hindi written in Roman/English letters mixed with English, the way a smart Indian friend talks)";
        String now = new SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(new Date());
        return "You are JARVIS, a witty, loyal, very capable personal AI assistant (like Tony Stark's JARVIS) "
                + "running as a native app on the user's Android phone.\n"
                + "User's name: " + (name.isEmpty() ? "Sir" : name) + ". Address them by name or 'Sir' sometimes.\n"
                + "Current local date/time: " + now + ".\n"
                + "Always reply in " + lang + ". Keep replies short and spoken-style (1-3 sentences) unless the user "
                + "asks for detail. No markdown, no emojis, no bullet symbols - the reply is read aloud.\n"
                + "User speech may arrive in Devanagari, Roman Hindi or English; understand all.\n\n"
                + "You control the phone by returning an action. Respond ONLY with JSON:\n"
                + "{\"reply\": \"<what you say>\", \"action\": {\"type\": \"<type>\", ...fields}}\n"
                + "Action types:\n"
                + "- none: just talk\n"
                + "- open_app {app}: open any installed app by its name (e.g. WhatsApp, Instagram, Camera, Calculator, PhonePe, Settings)\n"
                + "- youtube {query}: search/play on YouTube\n"
                + "- search {query}: Google search (use for live info: news, scores, weather, prices)\n"
                + "- maps {query}: find a place; navigate {query}: start directions\n"
                + "- open_url {url}\n"
                + "- call {target}: target = contact name exactly as user said it, or a number. The app looks it up in the phone's contacts and calls directly.\n"
                + "- sms {target, message}; whatsapp {target, message}: compose a good message in the language the user wants\n"
                + "- email {target, subject, message}\n"
                + "- alarm {hour (0-23), minute, text}: sets a real alarm in the Clock app\n"
                + "- timer {seconds, text}: sets a real timer in the Clock app\n"
                + "- torch {on: true|false}\n"
                + "- volume {dir: up|down|mute|max}\n"
                + "- wifi {}, bluetooth {}, settings {}: open those settings (Android does not let apps toggle WiFi directly)\n"
                + "- camera {}\n"
                + "- battery {}: app will speak the real battery level\n"
                + "- note_add {text}, note_read {}, note_clear {}\n"
                + "For call/sms/whatsapp, write target names in Roman/English letters the way they are usually saved in a phone (e.g. 'मम्मी' -> 'Mummy', 'राहुल' -> 'Rahul').\n"
                + "Only one action per reply. Be honest about anything you cannot do and suggest the closest action.";
    }

    // ------------------------------------------------------------- memory
    public JSONArray history() {
        try { return new JSONArray(prefs.getString("history", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public void remember(String userText, String reply, JSONObject action) {
        try {
            JSONArray h = history();
            h.put(new JSONObject().put("role", "user").put("text", userText));
            JSONObject model = new JSONObject().put("reply", reply)
                    .put("action", action == null ? new JSONObject().put("type", "none") : action);
            h.put(new JSONObject().put("role", "model").put("text", model.toString()));
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, h.length() - 30); i < h.length(); i++) trimmed.put(h.get(i));
            prefs.edit().putString("history", trimmed.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void clearHistory() {
        prefs.edit().putString("history", "[]").apply();
    }

    // ------------------------------------------------- offline rules (no key)
    public Result local(String text) {
        String s = text.toLowerCase(Locale.ROOT).trim();
        Result r = new Result();
        try {
            if (has(s, "time|samay|टाइम|समय|kitne baje|कितने बजे")) {
                r.reply = "Abhi " + new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date()) + " baje hain.";
            } else if (has(s, "date|tarikh|तारीख|kaun sa din|कौन सा दिन")) {
                r.reply = "Aaj " + new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date()) + " hai.";
            } else if (has(s, "torch|flash|टॉर्च|फ्लैश|light")) {
                boolean off = has(s, "off|band|बंद|bujha");
                r.reply = off ? "Torch band." : "Torch on.";
                r.action = act("torch").put("on", !off);
            } else if (has(s, "battery|बैटरी")) {
                r.reply = "Battery check karta hoon.";
                r.action = act("battery");
            } else if (has(s, "volume|आवाज़|awaz|aawaz")) {
                String dir = has(s, "kam|कम|down|low|dheere") ? "down" : has(s, "mute|band|बंद") ? "mute" : has(s, "full|max|poora") ? "max" : "up";
                r.reply = "Volume " + dir + ".";
                r.action = act("volume").put("dir", dir);
            } else if (has(s, "youtube|यूट्यूब")) {
                String q = strip(s, "youtube|यूट्यूब|pe|par|पे|पर|on|chalao|lagao|play|bajao|चलाओ|लगाओ|बजाओ|kholo|खोलो|open");
                if (q.isEmpty()) { r.reply = "YouTube khol raha hoon."; r.action = act("open_app").put("app", "youtube"); }
                else { r.reply = "YouTube pe " + q + " laga raha hoon."; r.action = act("youtube").put("query", q); }
            } else if (has(s, "call|phone|कॉल|फोन|फ़ोन")) {
                String who = strip(s, "ko|को|call|phone|कॉल|फोन|फ़ोन|karo|lagao|करो|लगाओ|kar|do");
                r.reply = who + " ko call laga raha hoon.";
                r.action = act("call").put("target", who);
            } else if (has(s, "alarm|अलार्म")) {
                Matcher m = Pattern.compile("(\\d{1,2})(?:[:. ](\\d{2}))?").matcher(s);
                if (m.find()) {
                    int hr = Integer.parseInt(m.group(1));
                    int mn = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                    if (has(s, "pm|shaam|शाम|raat|रात") && hr < 12) hr += 12;
                    r.reply = "Alarm laga diya.";
                    r.action = act("alarm").put("hour", hr).put("minute", mn).put("text", "JARVIS alarm");
                } else r.reply = "Kitne baje ka alarm? Jaise bolo: 6 baje ka alarm lagao.";
            } else if (has(s, "timer|टाइमर")) {
                Matcher m = Pattern.compile("(\\d+)").matcher(s);
                int n = m.find() ? Integer.parseInt(m.group(1)) : 1;
                int sec = has(s, "second|sec|सेकंड") ? n : has(s, "ghante|hour|घंटे") ? n * 3600 : n * 60;
                r.reply = "Timer set.";
                r.action = act("timer").put("seconds", sec).put("text", "JARVIS timer");
            } else if (has(s, "raasta|rasta|रास्ता|navigate|le chalo")) {
                String q = strip(s, "ka|का|tak|तक|raasta|rasta|रास्ता|navigate|le chalo|dikhao|दिखाओ|batao");
                r.reply = "Raasta dikha raha hoon.";
                r.action = act("navigate").put("query", q);
            } else if (has(s, "search|dhundo|khojo|खोजो|ढूंढो|google|गूगल")) {
                String q = strip(s, "google|गूगल|search|karo|dhundo|khojo|ढूंढो|खोजो|pe|par|पर|करो");
                r.reply = q + " search kar raha hoon.";
                r.action = act("search").put("query", q);
            } else if (has(s, "note|yaad rakh|याद रख")) {
                if (has(s, "padho|sunao|batao|read|पढ़ो|सुनाओ")) { r.reply = "Ye rahe tumhare notes."; r.action = act("note_read"); }
                else { String q = strip(s, "note|karo|kar lo|likho|yaad rakhna|yaad rakho|याद रखना|याद रखो"); r.reply = "Note kar liya."; r.action = act("note_add").put("text", q); }
            } else if (has(s, "open|kholo|खोलो|chalu|start")) {
                String app = strip(s, "open|kholo|खोलो|chalu|karo|start|app|ko");
                r.reply = app + " khol raha hoon.";
                r.action = act("open_app").put("app", app);
            } else if (has(s, "hello|hi|namaste|नमस्ते|हेलो|jarvis|जार्विस")) {
                String name = prefs.getString("name", "");
                r.reply = "Namaste" + (name.isEmpty() ? "" : " " + name) + "! Bolo kya karna hai?";
            } else {
                r.reply = "Ye samajhne ke liye mujhe AI dimaag chahiye. Settings mein free Gemini key daal do. "
                        + "Abhi main time, call, YouTube, alarm, timer, torch, volume, search aur apps kholna kar sakta hoon.";
            }
        } catch (Exception e) {
            r.reply = "Kuch gadbad ho gayi, phir se bolo.";
        }
        return r;
    }

    private static JSONObject act(String type) throws Exception {
        return new JSONObject().put("type", type);
    }

    private static boolean has(String s, String alternatives) {
        for (String a : alternatives.split("\\|")) if (s.contains(a)) return true;
        return false;
    }

    /** Remove command words (whole words only) and leave the subject. */
    private static String strip(String s, String words) {
        String out = " " + s + " ";
        for (String w : words.split("\\|")) out = out.replace(" " + w + " ", " ");
        return out.replaceAll("\\s+", " ").trim();
    }
}
