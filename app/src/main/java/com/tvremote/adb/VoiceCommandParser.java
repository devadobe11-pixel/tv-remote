package com.tvremote.adb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Maps spoken Android speech transcripts to TV actions or app-aware voice searches.
 * Handles explicit commands first, then routes movie/series/song titles to whichever
 * supported app (SmartTube, Prime Video, Netflix) is currently on screen.
 */
public final class VoiceCommandParser {

    public enum ActionType {
        SHELL,
        KEYS,
        TEXT_TYPE,
        SMART_SEARCH,
        SEARCH_YOUTUBE,
        SEARCH_PRIME,
        SEARCH_NETFLIX,
        QUERY_OR_DRAFT
    }

    public static final class ParsedCommand {
        public final ActionType type;
        public final String label;
        public final String payload;
        public final int[] keys;

        ParsedCommand(ActionType type, String label, String payload, int... keys) {
            this.type = type;
            this.label = label;
            this.payload = payload;
            this.keys = keys;
        }
    }

    // Default TV app launch commands (customizable for other Android TV launchers/ROMs).
    public static final String CMD_OPEN_YOUTUBE =
            "am start -n app.smarttube.fdroid/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity";
    public static final String CMD_OPEN_NETFLIX =
            "monkey -p com.netflix.mediaclient -c android.intent.category.LAUNCHER 1";
    public static final String CMD_OPEN_PRIME =
            "am start -n com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity";
    public static final String CMD_OPEN_HOTSTAR =
            "monkey -p in.startv.hotstar -c android.intent.category.LEANBACK_LAUNCHER 1";
    public static final String CMD_OPEN_MIRACAST =
            "monkey -p com.hisilicon.miracast 1";
    public static final String CMD_OPEN_INPUTS =
            "am start -n com.cvte.tv.globalui/.InputSourceActivity";
    public static final String CMD_OPEN_AURORA =
            "monkey -p com.aurora.store 1";
    public static final String CMD_OPEN_SETTINGS =
            "am start -a android.settings.SETTINGS";

    private VoiceCommandParser() {}

    public static String smartTubeSearchCmd(String query) {
        return "am start -n app.smarttube.fdroid/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity "
                + "-a android.intent.action.VIEW -d 'https://www.youtube.com/results?search_query="
                + urlEncode(query) + "'";
    }

    public static String primeSearchCmd(String query) {
        return "am start -n com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity "
                + "-a android.intent.action.VIEW -d 'https://app.primevideo.com/search?phrase="
                + urlEncode(query) + "'";
    }

    public static String netflixOpenSearchCmd() {
        return "am start -n com.netflix.mediaclient/.ui.launch.NetflixComLaunchActivity "
                + "-a android.intent.action.VIEW -d 'https://www.netflix.com/search'";
    }

    public static ParsedCommand parse(String transcript) {
        if (transcript == null) {
            return new ParsedCommand(ActionType.QUERY_OR_DRAFT, "Nothing heard", "");
        }
        String raw = transcript.trim();
        if (raw.isEmpty()) {
            return new ParsedCommand(ActionType.QUERY_OR_DRAFT, "Nothing heard", "");
        }

        String norm = raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();

        // 1. Explicit "type ..." leaves text unsubmitted in focused field.
        String typeQuery = stripPrefix(norm, raw, "type ", "write ");
        if (typeQuery != null && !typeQuery.isEmpty()) {
            return new ParsedCommand(ActionType.TEXT_TYPE, "Type: " + typeQuery, typeQuery);
        }

        // 2. Explicit app-targeted search ("search X on youtube/prime/netflix").
        String searchBody = stripPrefix(norm, raw, "search for ", "search ", "find ", "look up ");
        if (searchBody != null && !searchBody.isEmpty()) {
            return routeTargetedSearch(searchBody);
        }

        // 3. Open / Launch apps ("open netflix", or standalone app name "netflix").
        String appTarget = norm
                .replaceFirst("^(open|launch|start|go to|switch to) ", "")
                .replaceFirst("^the ", "")
                .trim();
        ParsedCommand appCmd = matchApp(appTarget);
        if (appCmd != null) {
            return appCmd;
        }

        // 4. Volume, Playback, Navigation, Power (uses fast KEYS stream).
        if (norm.contains("volume up") || norm.equals("louder") || norm.contains("increase volume")) {
            return new ParsedCommand(ActionType.KEYS, "Volume Up", "", 24, 24, 24);
        }
        if (norm.contains("volume down") || norm.equals("quieter") || norm.contains("lower volume") || norm.contains("decrease volume")) {
            return new ParsedCommand(ActionType.KEYS, "Volume Down", "", 25, 25, 25);
        }
        if (norm.equals("mute") || norm.equals("unmute") || norm.contains("mute tv") || norm.contains("mute volume")) {
            return new ParsedCommand(ActionType.KEYS, "Mute", "", 164);
        }
        if (norm.equals("play") || norm.equals("pause") || norm.equals("resume") || norm.equals("stop")
                || norm.equals("play pause")) {
            return new ParsedCommand(ActionType.KEYS, "Play / Pause", "", 85);
        }
        if (norm.equals("next") || norm.equals("next video") || norm.equals("skip")) {
            return new ParsedCommand(ActionType.KEYS, "Next", "", 87);
        }
        if (norm.equals("previous") || norm.equals("previous video")) {
            return new ParsedCommand(ActionType.KEYS, "Previous", "", 88);
        }
        if (norm.equals("home") || norm.equals("go home") || norm.equals("home screen")) {
            return new ParsedCommand(ActionType.KEYS, "Home", "", 3);
        }
        if (norm.equals("back") || norm.equals("go back")) {
            return new ParsedCommand(ActionType.KEYS, "Back", "", 4);
        }
        if (norm.equals("up") || norm.equals("go up")) {
            return new ParsedCommand(ActionType.KEYS, "Up", "", 19);
        }
        if (norm.equals("down") || norm.equals("go down")) {
            return new ParsedCommand(ActionType.KEYS, "Down", "", 20);
        }
        if (norm.equals("left") || norm.equals("go left")) {
            return new ParsedCommand(ActionType.KEYS, "Left", "", 21);
        }
        if (norm.equals("right") || norm.equals("go right")) {
            return new ParsedCommand(ActionType.KEYS, "Right", "", 22);
        }
        if (norm.equals("ok") || norm.equals("okay") || norm.equals("select") || norm.equals("enter") || norm.equals("click")) {
            return new ParsedCommand(ActionType.KEYS, "OK", "", 23);
        }
        if (norm.equals("power") || norm.equals("turn off") || norm.equals("turn off tv") || norm.equals("sleep")) {
            return new ParsedCommand(ActionType.KEYS, "Power", "", 26);
        }

        // 5. Bare movie/series/song name: MainActivity auto-searches if a supported app is active.
        return routeTargetedSearchOrQuery(raw);
    }

    private static ParsedCommand routeTargetedSearch(String body) {
        String lower = body.toLowerCase(Locale.US);
        String q;
        if ((q = stripSuffix(body, lower, " on youtube", " in youtube", " on smarttube", " in smarttube", " on smart tube")) != null) {
            return new ParsedCommand(ActionType.SEARCH_YOUTUBE, "YouTube Search: " + q, q);
        }
        if ((q = stripSuffix(body, lower, " on prime video", " in prime video", " on prime", " in prime", " on amazon prime", " on amazon")) != null) {
            return new ParsedCommand(ActionType.SEARCH_PRIME, "Prime Search: " + q, q);
        }
        if ((q = stripSuffix(body, lower, " on netflix", " in netflix")) != null) {
            return new ParsedCommand(ActionType.SEARCH_NETFLIX, "Netflix Search: " + q, q);
        }
        return new ParsedCommand(ActionType.SMART_SEARCH, "Search: " + body, body);
    }

    private static ParsedCommand routeTargetedSearchOrQuery(String raw) {
        String lower = raw.toLowerCase(Locale.US);
        String q;
        if ((q = stripSuffix(raw, lower, " on youtube", " in youtube", " on smarttube", " on smart tube")) != null) {
            return new ParsedCommand(ActionType.SEARCH_YOUTUBE, "YouTube Search: " + q, q);
        }
        if ((q = stripSuffix(raw, lower, " on prime video", " on prime", " on amazon prime")) != null) {
            return new ParsedCommand(ActionType.SEARCH_PRIME, "Prime Search: " + q, q);
        }
        if ((q = stripSuffix(raw, lower, " on netflix")) != null) {
            return new ParsedCommand(ActionType.SEARCH_NETFLIX, "Netflix Search: " + q, q);
        }
        return new ParsedCommand(ActionType.QUERY_OR_DRAFT, "Title/Query: \"" + raw + "\"", raw);
    }

    private static ParsedCommand matchApp(String target) {
        if (target.equals("youtube") || target.equals("smarttube") || target.equals("smart tube") || target.equals("you tube")) {
            return new ParsedCommand(ActionType.SHELL, "Open SmartTube", CMD_OPEN_YOUTUBE);
        }
        if (target.equals("netflix")) {
            return new ParsedCommand(ActionType.SHELL, "Open Netflix", CMD_OPEN_NETFLIX);
        }
        if (target.equals("prime") || target.equals("prime video") || target.equals("amazon prime") || target.equals("amazon")) {
            return new ParsedCommand(ActionType.SHELL, "Open Prime Video", CMD_OPEN_PRIME);
        }
        if (target.equals("hotstar") || target.equals("jiohotstar") || target.equals("jio hotstar") || target.equals("disney")) {
            return new ParsedCommand(ActionType.SHELL, "Open JioHotstar", CMD_OPEN_HOTSTAR);
        }
        if (target.equals("miracast") || target.equals("cast") || target.equals("mirror") || target.equals("screen mirror")) {
            return new ParsedCommand(ActionType.SHELL, "Open Miracast", CMD_OPEN_MIRACAST);
        }
        if (target.equals("inputs") || target.equals("input") || target.equals("hdmi") || target.equals("source") || target.equals("av")) {
            return new ParsedCommand(ActionType.SHELL, "Open Inputs", CMD_OPEN_INPUTS);
        }
        if (target.equals("aurora") || target.equals("aurora store") || target.equals("app store") || target.equals("store")) {
            return new ParsedCommand(ActionType.SHELL, "Open Aurora Store", CMD_OPEN_AURORA);
        }
        if (target.equals("settings") || target.equals("tv settings")) {
            return new ParsedCommand(ActionType.SHELL, "Open Settings", CMD_OPEN_SETTINGS);
        }
        return null;
    }

    private static String stripPrefix(String norm, String raw, String... prefixes) {
        for (String p : prefixes) {
            if (norm.startsWith(p) && raw.length() >= p.length()) {
                String lowerRaw = raw.toLowerCase(Locale.US);
                int idx = lowerRaw.indexOf(p.trim());
                if (idx >= 0) {
                    return raw.substring(idx + p.trim().length()).trim();
                }
                return norm.substring(p.length()).trim();
            }
        }
        return null;
    }

    private static String stripSuffix(String raw, String lower, String... suffixes) {
        for (String s : suffixes) {
            if (lower.endsWith(s)) {
                String trimmed = raw.substring(0, raw.length() - s.length()).trim();
                if (!trimmed.isEmpty()) return trimmed;
            }
        }
        return null;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s.replace(' ', '+');
        }
    }
}
