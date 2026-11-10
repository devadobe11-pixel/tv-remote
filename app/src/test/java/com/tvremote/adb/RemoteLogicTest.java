package com.tvremote.adb;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteLogicTest {

    @Test
    public void escapeForInputText_handlesSpacesQuotesAndControlChars() {
        assertEquals("", AdbClient.escapeForInputText(null));
        assertEquals("", AdbClient.escapeForInputText("   "));
        assertEquals("'hello%sworld'", AdbClient.escapeForInputText("  hello   world  "));
        assertEquals("'don'\\''t%sstop'", AdbClient.escapeForInputText("don't stop"));
        assertEquals("'a100'", AdbClient.escapeForInputText("a100%"));
        assertEquals("'hi'", AdbClient.escapeForInputText("hi🔥"));
    }

    @Test
    public void voiceParser_handlesOpenAppsTargetedSearchesAndBareTitles() {
        VoiceCommandParser.ParsedCommand yt = VoiceCommandParser.parse("Open YouTube");
        assertEquals(VoiceCommandParser.ActionType.SHELL, yt.type);
        assertEquals(VoiceCommandParser.CMD_OPEN_YOUTUBE, yt.payload);

        VoiceCommandParser.ParsedCommand prime = VoiceCommandParser.parse("prime video");
        assertEquals(VoiceCommandParser.ActionType.SHELL, prime.type);
        assertEquals(VoiceCommandParser.CMD_OPEN_PRIME, prime.payload);

        VoiceCommandParser.ParsedCommand smartSearch = VoiceCommandParser.parse("search Arijit Singh");
        assertEquals(VoiceCommandParser.ActionType.SMART_SEARCH, smartSearch.type);
        assertEquals("Arijit Singh", smartSearch.payload);

        VoiceCommandParser.ParsedCommand onPrime = VoiceCommandParser.parse("search Mirzapur on Prime Video");
        assertEquals(VoiceCommandParser.ActionType.SEARCH_PRIME, onPrime.type);
        assertEquals("Mirzapur", onPrime.payload);

        VoiceCommandParser.ParsedCommand onNetflix = VoiceCommandParser.parse("Money Heist on Netflix");
        assertEquals(VoiceCommandParser.ActionType.SEARCH_NETFLIX, onNetflix.type);
        assertEquals("Money Heist", onNetflix.payload);

        VoiceCommandParser.ParsedCommand onYt = VoiceCommandParser.parse("search kesariya on youtube");
        assertEquals(VoiceCommandParser.ActionType.SEARCH_YOUTUBE, onYt.type);
        assertEquals("kesariya", onYt.payload);

        VoiceCommandParser.ParsedCommand typeOnly = VoiceCommandParser.parse("type hello world");
        assertEquals(VoiceCommandParser.ActionType.TEXT_TYPE, typeOnly.type);
        assertEquals("hello world", typeOnly.payload);

        VoiceCommandParser.ParsedCommand vol = VoiceCommandParser.parse("volume up");
        assertEquals(VoiceCommandParser.ActionType.KEYS, vol.type);
        assertArrayEquals(new int[]{24, 24, 24}, vol.keys);

        VoiceCommandParser.ParsedCommand bareMovie = VoiceCommandParser.parse("Stranger Things");
        assertEquals(VoiceCommandParser.ActionType.QUERY_OR_DRAFT, bareMovie.type);
        assertEquals("Stranger Things", bareMovie.payload);
    }

    @Test
    public void voiceSearchCommands_encodeQueriesCleanly() {
        assertTrue(VoiceCommandParser.smartTubeSearchCmd("arijit singh").contains("search_query=arijit+singh"));
        assertTrue(VoiceCommandParser.primeSearchCmd("the family man").contains("phrase=the+family+man"));
        assertTrue(VoiceCommandParser.netflixOpenSearchCmd().contains("https://www.netflix.com/search"));
    }

    @Test
    public void tvDiscovery_parsesOctetSafely() {
        assertEquals(143, TvDiscovery.parseLastOctet("192.168.43.143"));
        assertEquals(-1, TvDiscovery.parseLastOctet("192.168.43.0"));
        assertEquals(-1, TvDiscovery.parseLastOctet("192.168.43.255"));
        assertEquals(-1, TvDiscovery.parseLastOctet("invalid"));
    }

    @Test
    public void mainActivity_detectsStaleKeyTaps() {
        assertFalse(MainActivity.isStaleTap(1000L, 1500L));
        assertFalse(MainActivity.isStaleTap(1000L, 2500L));
        assertTrue(MainActivity.isStaleTap(1000L, 2501L));
    }
}
