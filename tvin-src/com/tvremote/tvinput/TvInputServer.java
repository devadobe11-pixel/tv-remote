package com.tvremote.tvinput;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Persistent shell input injector run via:
 *   CLASSPATH=/data/local/tmp/tvin.dex app_process / com.tvremote.tvinput.TvInputServer
 * Eliminates per-keypress Zygote/app_process startup lag (~800ms -> ~10ms).
 */
public final class TvInputServer {
    private static final int INJECT_ASYNC = 0;

    public static void main(String[] args) throws Exception {
        Class<?> imClass = Class.forName("android.hardware.input.InputManager");
        Method getInstance = imClass.getMethod("getInstance");
        Object im = getInstance.invoke(null);
        Method inject = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

        System.out.println("READY");
        System.out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.equals("q")) break;

            try {
                if (line.startsWith("k ")) {
                    String[] parts = line.substring(2).trim().split("\\s+");
                    for (String p : parts) {
                        if (!p.isEmpty()) {
                            injectKey(im, inject, Integer.parseInt(p));
                        }
                    }
                    System.out.println("OK");
                } else if (line.startsWith("t ") || line.startsWith("s ")) {
                    boolean submit = line.startsWith("s ");
                    String text = line.substring(2);
                    injectText(im, inject, kcm, text);
                    if (submit) {
                        injectKey(im, inject, KeyEvent.KEYCODE_ENTER);
                    }
                    System.out.println("OK");
                } else if (line.equals("c")) {
                    // Clear focused text box: Ctrl+A then Backspace
                    long now = SystemClock.uptimeMillis();
                    inject.invoke(im, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A,
                            0, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), INJECT_ASYNC);
                    inject.invoke(im, new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A,
                            0, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), INJECT_ASYNC);
                    injectKey(im, inject, KeyEvent.KEYCODE_DEL);
                    System.out.println("OK");
                }
            } catch (Throwable t) {
                System.out.println("ERR:" + t.getMessage());
            }
            System.out.flush();
        }
    }

    private static void injectKey(Object im, Method inject, int keyCode) throws Exception {
        long now = SystemClock.uptimeMillis();
        int source = (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER)
                ? InputDevice.SOURCE_DPAD : InputDevice.SOURCE_KEYBOARD;
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        inject.invoke(im, down, INJECT_ASYNC);
        inject.invoke(im, up, INJECT_ASYNC);
    }

    private static void injectText(Object im, Method inject, KeyCharacterMap kcm, String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        KeyEvent[] events = kcm.getEvents(text.toCharArray());
        if (events != null) {
            for (KeyEvent e : events) {
                long now = SystemClock.uptimeMillis();
                KeyEvent adjusted = KeyEvent.changeTimeRepeat(e, now, 0);
                inject.invoke(im, adjusted, INJECT_ASYNC);
            }
            return;
        }
        // Fallback char-by-char if an unsupported glyph caused null.
        char[] one = new char[1];
        for (int i = 0; i < text.length(); i++) {
            one[0] = text.charAt(i);
            KeyEvent[] evs = kcm.getEvents(one);
            if (evs != null) {
                for (KeyEvent e : evs) {
                    long now = SystemClock.uptimeMillis();
                    inject.invoke(im, KeyEvent.changeTimeRepeat(e, now, 0), INJECT_ASYNC);
                }
            }
        }
    }
}
