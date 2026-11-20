package com.jarvis.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Everything JARVIS can physically do on the phone. */
public class Actions {

    private final Activity a;
    private final SharedPreferences prefs;
    /** True when the last action opened another app (so we don't start listening again over it). */
    public boolean leftApp = false;

    public Actions(Activity a, SharedPreferences prefs) {
        this.a = a;
        this.prefs = prefs;
    }

    /** Runs the action. Returns text that should replace JARVIS's reply, or null to keep the AI's reply. */
    public String run(JSONObject act) {
        leftApp = false;
        if (act == null) return null;
        String type = act.optString("type", "none");
        String q = act.optString("query", act.optString("text", "")).trim();
        try {
            switch (type) {
                case "youtube":
                    return open(Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)));
                case "search": {
                    Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
                    i.putExtra(SearchManager.QUERY, q);
                    if (start(i)) { leftApp = true; return null; }
                    return open(Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)));
                }
                case "maps":
                    return open(Uri.parse("geo:0,0?q=" + Uri.encode(q)));
                case "navigate": {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)));
                    if (start(i)) { leftApp = true; return null; }
                    return open(Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(q)));
                }
                case "open_url": {
                    String u = act.optString("url", q);
                    if (!u.startsWith("http")) u = "https://" + u;
                    return open(Uri.parse(u));
                }
                case "open_app":
                    return launchApp(act.optString("app", q));
                case "call": {
                    String target = act.optString("target", "");
                    String num = resolveNumber(target);
                    if (num == null) return missing(target);
                    boolean direct = a.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
                    Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + num));
                    if (start(i)) { leftApp = true; return null; }
                    return "Call nahi lag paya.";
                }
                case "sms": {
                    String target = act.optString("target", "");
                    String num = resolveNumber(target);
                    if (num == null) return missing(target);
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + num));
                    i.putExtra("sms_body", act.optString("message", ""));
                    if (start(i)) { leftApp = true; return null; }
                    return "SMS app nahi mila.";
                }
                case "whatsapp": {
                    String target = act.optString("target", "").trim();
                    String msg = act.optString("message", "");
                    String url;
                    if (target.isEmpty()) {
                        url = "https://api.whatsapp.com/send?text=" + Uri.encode(msg);
                    } else {
                        String num = resolveNumber(target);
                        if (num == null) return missing(target);
                        url = "https://api.whatsapp.com/send?phone=" + waNumber(num) + "&text=" + Uri.encode(msg);
                    }
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    if (isInstalled("com.whatsapp")) i.setPackage("com.whatsapp");
                    else if (isInstalled("com.whatsapp.w4b")) i.setPackage("com.whatsapp.w4b");
                    if (start(i)) { leftApp = true; return null; }
                    return "WhatsApp nahi khul paya.";
                }
                case "email": {
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + act.optString("target", "")));
                    i.putExtra(Intent.EXTRA_SUBJECT, act.optString("subject", ""));
                    i.putExtra(Intent.EXTRA_TEXT, act.optString("message", ""));
                    if (start(i)) { leftApp = true; return null; }
                    return "Email app nahi mila.";
                }
                case "alarm": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                    i.putExtra(AlarmClock.EXTRA_HOUR, clamp(act.optInt("hour", 7), 0, 23));
                    i.putExtra(AlarmClock.EXTRA_MINUTES, clamp(act.optInt("minute", 0), 0, 59));
                    i.putExtra(AlarmClock.EXTRA_MESSAGE, act.optString("text", "JARVIS"));
                    i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    if (start(i)) return null;
                    return "Clock app ne alarm nahi liya.";
                }
                case "timer": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
                    i.putExtra(AlarmClock.EXTRA_LENGTH, clamp(act.optInt("seconds", 60), 1, 86400));
                    i.putExtra(AlarmClock.EXTRA_MESSAGE, act.optString("text", "JARVIS"));
                    i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    if (start(i)) return null;
                    return "Clock app ne timer nahi liya.";
                }
                case "torch":
                    return torch(act.optBoolean("on", true));
                case "volume":
                    return volume(act.optString("dir", "up"));
                case "wifi": {
                    Intent i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_WIFI) : new Intent(Settings.ACTION_WIFI_SETTINGS);
                    if (start(i)) { leftApp = true; return null; }
                    return null;
                }
                case "bluetooth":
                    if (start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) leftApp = true;
                    return null;
                case "settings":
                    if (start(new Intent(Settings.ACTION_SETTINGS))) leftApp = true;
                    return null;
                case "camera":
                    if (start(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) { leftApp = true; return null; }
                    return "Camera nahi khula.";
                case "battery": {
                    BatteryManager bm = (BatteryManager) a.getSystemService(Context.BATTERY_SERVICE);
                    int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    boolean charging = bm.isCharging();
                    return "Battery " + pct + " percent hai" + (charging ? ", aur charge ho rahi hai." : ".");
                }
                case "note_add": {
                    if (q.isEmpty()) return "Kya note karna hai?";
                    JSONArray n = notes();
                    n.put(new JSONObject().put("t", q).put("d", new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new Date())));
                    prefs.edit().putString("notes", n.toString()).apply();
                    return null;
                }
                case "note_read": {
                    JSONArray n = notes();
                    if (n.length() == 0) return "Koi note nahi hai.";
                    StringBuilder sb = new StringBuilder("Tumhare notes: ");
                    for (int i = 0; i < n.length(); i++) sb.append(i + 1).append(". ").append(n.getJSONObject(i).optString("t")).append(". ");
                    return sb.toString().trim();
                }
                case "note_clear":
                    prefs.edit().putString("notes", "[]").apply();
                    return null;
                default:
                    return null;
            }
        } catch (Exception e) {
            return "Ye kaam karte waqt dikkat aayi: " + e.getMessage();
        }
    }

    // ----------------------------------------------------------- helpers
    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private String open(Uri uri) {
        if (start(new Intent(Intent.ACTION_VIEW, uri))) { leftApp = true; return null; }
        return "Isko kholne ke liye phone mein app nahi mila.";
    }

    private boolean isInstalled(String pkg) {
        try { a.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String launchApp(String name) {
        String want = norm(name);
        if (want.isEmpty()) return "Kaunsa app kholu?";
        // Common Hindi spellings / nicknames
        String[][] alias = {
                {"व्हाट्सएप", "whatsapp"}, {"वॉट्सऐप", "whatsapp"}, {"यूट्यूब", "youtube"}, {"इंस्टाग्राम", "instagram"},
                {"insta", "instagram"}, {"कैमरा", "camera"}, {"कैलकुलेटर", "calculator"}, {"सेटिंग", "settings"},
                {"fb", "facebook"}, {"gpay", "googlepay"}, {"phonepay", "phonepe"}, {"playstore", "playstore"},
                {"गैलरी", "gallery"}, {"photos", "photos"}, {"crome", "chrome"}, {"क्रोम", "chrome"}
        };
        for (String[] p : alias) if (want.contains(norm(p[0]))) { want = p[1]; break; }

        PackageManager pm = a.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = norm(String.valueOf(ri.loadLabel(pm)));
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            if (pkg.equals(a.getPackageName())) continue;
            int score = 0;
            if (label.equals(want)) score = 100;
            else if (label.startsWith(want)) score = 80;
            else if (label.contains(want)) score = 60;
            else if (want.contains(label) && label.length() >= 3) score = 50;
            else if (pkg.contains(want)) score = 40;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best != null) {
            Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
            if (launch != null && start(launch)) { leftApp = true; return null; }
        }
        // Not installed: try the website, else Play Store search
        if (start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(name))))) {
            leftApp = true;
            return "\"" + name + "\" phone mein nahi mila, Play Store mein dhoondh raha hoon.";
        }
        return "\"" + name + "\" naam ka app nahi mila.";
    }

    private String torch(boolean on) {
        try {
            CameraManager cm = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) { cm.setTorchMode(id, on); return null; }
            }
            return "Is phone mein flash nahi mila.";
        } catch (Exception e) {
            return "Torch abhi kisi aur app ne pakdi hui hai (camera band karke try karo).";
        }
    }

    private String volume(String dir) {
        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        int s = AudioManager.STREAM_MUSIC;
        switch (dir) {
            case "down": am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, 0); break;
            case "mute": am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); break;
            case "max": am.setStreamVolume(s, am.getStreamMaxVolume(s), AudioManager.FLAG_SHOW_UI); break;
            default: am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0);
                am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, 0);
        }
        return null;
    }

    private String missing(String target) {
        return "\"" + target + "\" contacts mein nahi mila. Naam waise bolo jaise phone mein save hai, ya seedha number bolo.";
    }

    private static String waNumber(String n) {
        String d = n.replaceAll("\\D", "");
        if (d.startsWith("0")) d = d.substring(1);
        if (d.length() == 10) d = "91" + d;
        return d;
    }

    /** Number spoken directly, or looked up by name in the phone's real contacts. */
    public String resolveNumber(String target) {
        if (target == null) return null;
        target = target.trim();
        String digits = target.replaceAll("[^0-9+]", "");
        if (digits.replace("+", "").length() >= 7) return digits;
        if (target.isEmpty()) return null;
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;

        String[] tries = {target, target.replaceAll("(?i)\\b(ji|bhai|bhaiya|didi|sir|madam)\\b", "").trim()};
        for (String t : tries) {
            if (t.isEmpty()) continue;
            Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(t));
            try (Cursor c = a.getContentResolver().query(uri,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0).replaceAll("[^0-9+]", "");
            } catch (Exception ignored) {}
        }
        return null;
    }

    private JSONArray notes() {
        try { return new JSONArray(prefs.getString("notes", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
}
