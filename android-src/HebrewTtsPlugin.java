package com.luach.mischakim;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * הקראה בעברית עם מנוע הדיבור של אנדרואיד:
 * speak / stop / synthesizeToFile (WAV לצורך MP3) / check / openSettings
 */
@CapacitorPlugin(name = "HebrewTts")
public class HebrewTtsPlugin extends Plugin {

    private TextToSpeech tts;
    private volatile int initStatus = Integer.MIN_VALUE;
    private Locale hebrew = null;
    private final Object lock = new Object();
    private final List<Runnable> waiting = new ArrayList<>();
    private final Map<String, Runnable> doneCallbacks = new HashMap<>();
    private final Map<String, Runnable> errorCallbacks = new HashMap<>();
    private PluginCall activeSpeak = null;
    private int seq = 0;

    @Override
    public void load() {
        tts = new TextToSpeech(getContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                hebrew = pickHebrew();
                if (hebrew != null) tts.setLanguage(hebrew);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {}

                    @Override
                    public void onDone(String id) { fire(id, true); }

                    @Override
                    public void onError(String id) { fire(id, false); }

                    @Override
                    public void onError(String id, int code) { fire(id, false); }

                    @Override
                    public void onStop(String id, boolean interrupted) { fire(id, true); }
                });
            }
            List<Runnable> copy;
            synchronized (lock) {
                initStatus = status;
                copy = new ArrayList<>(waiting);
                waiting.clear();
            }
            for (Runnable r : copy) r.run();
        });
    }

    private void whenReady(Runnable r) {
        boolean now;
        synchronized (lock) {
            now = initStatus != Integer.MIN_VALUE;
            if (!now) waiting.add(r);
        }
        if (now) r.run();
    }

    private boolean ready() {
        return initStatus == TextToSpeech.SUCCESS;
    }

    private void fire(String id, boolean ok) {
        Runnable r;
        synchronized (lock) {
            Runnable d = doneCallbacks.remove(id);
            Runnable e = errorCallbacks.remove(id);
            r = ok ? d : e;
        }
        if (r != null) r.run();
    }

    private Locale pickHebrew() {
        Locale[] candidates = new Locale[] {
            Locale.forLanguageTag("he-IL"), new Locale("iw", "IL"), new Locale("he"), new Locale("iw")
        };
        for (Locale l : candidates) {
            try {
                if (tts.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) return l;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.matches("^-+$")) continue;
            while (line.length() > max) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                int cut = line.lastIndexOf(' ', max);
                if (cut < max / 2) cut = max;
                out.add(line.substring(0, cut));
                line = line.substring(cut).trim();
            }
            if (cur.length() + line.length() + 1 > max && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private int maxLen() {
        int m = 3900;
        try { m = Math.min(m, TextToSpeech.getMaxSpeechInputLength() - 100); } catch (Exception ignored) {}
        return Math.max(500, m);
    }

    private String notReadyMessage() {
        if (!ready()) return "מנוע הדיבור של הטלפון לא זמין. התקן את 'שירותי דיבור של Google'.";
        return "אין קול עברי מותקן. בהגדרות: נגישות ← המרת טקסט לדיבור ← מנוע Google ← התקן נתוני קול ← עברית.";
    }

    @PluginMethod
    public void speak(PluginCall call) {
        final String text = call.getString("text", "");
        final Float rateObj = call.getFloat("rate", 1.0f);
        final float rate = rateObj == null ? 1.0f : rateObj;
        whenReady(() -> {
            if (!ready() || hebrew == null) { call.reject(notReadyMessage()); return; }
            List<String> chunks = split(text, maxLen());
            if (chunks.isEmpty()) { call.resolve(); return; }
            PluginCall previous;
            synchronized (lock) {
                previous = activeSpeak;
                activeSpeak = call;
                doneCallbacks.clear();
                errorCallbacks.clear();
            }
            tts.stop();
            if (previous != null) previous.resolve();
            tts.setLanguage(hebrew);
            tts.setSpeechRate(rate);
            int batch = ++seq;
            for (int i = 0; i < chunks.size(); i++) {
                final String id = "speak-" + batch + "-" + i;
                if (i == chunks.size() - 1) {
                    synchronized (lock) {
                        doneCallbacks.put(id, () -> finishSpeak(call, null));
                        errorCallbacks.put(id, () -> finishSpeak(call, "שגיאה בהקראה"));
                    }
                }
                Bundle params = new Bundle();
                int res = tts.speak(chunks.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, id);
                if (res == TextToSpeech.ERROR) { finishSpeak(call, "שגיאה בהקראה"); return; }
            }
        });
    }

    private void finishSpeak(PluginCall call, String error) {
        synchronized (lock) {
            if (activeSpeak != call) return;
            activeSpeak = null;
        }
        if (error == null) call.resolve(); else call.reject(error);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        PluginCall speaking;
        synchronized (lock) {
            speaking = activeSpeak;
            activeSpeak = null;
            doneCallbacks.clear();
            errorCallbacks.clear();
        }
        if (tts != null) tts.stop();
        if (speaking != null) speaking.resolve();
        call.resolve();
    }

    @PluginMethod
    public void synthesizeToFile(PluginCall call) {
        final String text = call.getString("text", "");
        final Float rateObj = call.getFloat("rate", 1.0f);
        final float rate = rateObj == null ? 1.0f : rateObj;
        whenReady(() -> {
            if (!ready() || hebrew == null) { call.reject(notReadyMessage()); return; }
            final List<String> chunks = split(text, maxLen());
            if (chunks.isEmpty()) { call.reject("אין טקסט להקראה"); return; }
            tts.setLanguage(hebrew);
            tts.setSpeechRate(rate);
            final File dir = getContext().getCacheDir();
            final List<File> parts = new ArrayList<>();
            final int batch = ++seq;
            final int[] remaining = new int[] { chunks.size() };
            final boolean[] failed = new boolean[] { false };
            for (int i = 0; i < chunks.size(); i++) {
                final File f = new File(dir, "tts-part-" + batch + "-" + i + ".wav");
                parts.add(f);
                final String id = "file-" + batch + "-" + i;
                synchronized (lock) {
                    doneCallbacks.put(id, () -> {
                        boolean last;
                        synchronized (lock) { remaining[0]--; last = remaining[0] == 0 && !failed[0]; }
                        if (last) finishFile(call, parts, new File(dir, "luach-tts.wav"));
                    });
                    errorCallbacks.put(id, () -> {
                        boolean first;
                        synchronized (lock) { first = !failed[0]; failed[0] = true; }
                        if (first) call.reject("יצירת קובץ השמע נכשלה");
                    });
                }
                Bundle params = new Bundle();
                int res = tts.synthesizeToFile(chunks.get(i), params, f, id);
                if (res == TextToSpeech.ERROR) {
                    boolean first;
                    synchronized (lock) { first = !failed[0]; failed[0] = true; }
                    if (first) call.reject("יצירת קובץ השמע נכשלה");
                    return;
                }
            }
        });
    }

    private void finishFile(PluginCall call, List<File> parts, File out) {
        try {
            byte[] fmt = null;
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            for (File f : parts) {
                byte[] wav = readAll(f);
                int off = 12;
                while (off + 8 <= wav.length) {
                    String id = new String(wav, off, 4, "US-ASCII");
                    int len = le32(wav, off + 4);
                    if (len < 0 || off + 8 + len > wav.length) len = wav.length - off - 8;
                    if (id.equals("fmt ") && fmt == null) {
                        fmt = new byte[len];
                        System.arraycopy(wav, off + 8, fmt, 0, len);
                    } else if (id.equals("data")) {
                        pcm.write(wav, off + 8, len);
                    }
                    off += 8 + len + (len % 2);
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            if (fmt == null) throw new IOException("no fmt chunk");
            byte[] data = pcm.toByteArray();
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write("RIFF".getBytes("US-ASCII"));
                os.write(le32bytes(4 + (8 + fmt.length) + (8 + data.length)));
                os.write("WAVE".getBytes("US-ASCII"));
                os.write("fmt ".getBytes("US-ASCII"));
                os.write(le32bytes(fmt.length));
                os.write(fmt);
                os.write("data".getBytes("US-ASCII"));
                os.write(le32bytes(data.length));
                os.write(data);
            }
            JSObject ret = new JSObject();
            ret.put("path", out.getAbsolutePath());
            ret.put("uri", Uri.fromFile(out).toString());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("יצירת קובץ השמע נכשלה: " + e.getMessage());
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static byte[] le32bytes(int v) {
        return new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) };
    }

    @PluginMethod
    public void check(PluginCall call) {
        whenReady(() -> {
            JSObject ret = new JSObject();
            ret.put("ready", ready());
            ret.put("hebrew", ready() && hebrew != null);
            String engine = "";
            try { if (tts != null) engine = tts.getDefaultEngine(); } catch (Exception ignored) {}
            ret.put("engine", engine == null ? "" : engine);
            if (!ready()) ret.put("error", "מנוע הדיבור לא נטען");
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            Intent i = new Intent("com.android.settings.TTS_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
        }
    }
}
