package com.tvremote.adb;

import android.content.res.AssetManager;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal pure-Java ADB-over-TCP client (port 5555) with a persistent `tvin.dex` shell stream
 * for ~15ms key/text injection instead of spawning `app_process` on every tap.
 */
public final class AdbClient {
    public static final int DEFAULT_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 6000;
    private static final String REMOTE_DEX_PATH = "/data/local/tmp/tvin_v3.dex";

    private static final int A_CNXN = 0x4E584E43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4E45504F;
    private static final int A_OKAY = 0x59414B4F;
    private static final int A_CLSE = 0x45534C43;
    private static final int A_WRTE = 0x45545257;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;

    private final AtomicInteger nextLocalId = new AtomicInteger(1);
    private byte[] tvinDexBytes;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private String connectedHost;

    private int fastLocalId = 0;
    private int fastRemoteId = 0;

    public synchronized void setAssetManager(AssetManager assets) {
        if (assets == null || tvinDexBytes != null) return;
        try (InputStream is = assets.open("tvin.dex")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
            }
            tvinDexBytes = buf.toByteArray();
        } catch (IOException ignored) {
            tvinDexBytes = null;
        }
    }

    public synchronized void connect(String host) throws IOException {
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("TV IP is empty");
        }
        closeQuietly();
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host.trim(), DEFAULT_PORT), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);

        this.socket = s;
        this.in = new DataInputStream(s.getInputStream());
        this.out = s.getOutputStream();

        byte[] banner = "host::tv-remote\0".getBytes(StandardCharsets.UTF_8);
        sendPacket(A_CNXN, VERSION, MAX_DATA, banner);

        Packet resp = readPacket();
        if (resp.command == A_AUTH) {
            closeQuietly();
            throw new IOException("TV requested ADB RSA auth (set ro.adb.secure=0)");
        }
        if (resp.command != A_CNXN) {
            closeQuietly();
            throw new IOException("Unexpected ADB handshake reply: 0x" + Integer.toHexString(resp.command));
        }
        this.connectedHost = host.trim();

        // Warm up fast input stream immediately on connect (best-effort; falls back cleanly if unavailable).
        ensureFastInputQuietly();
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized String shell(String command) throws IOException {
        ensureConnected();
        try {
            return runShellOnce(command);
        } catch (IOException firstErr) {
            String host = this.connectedHost;
            if (host == null) throw firstErr;
            connect(host);
            return runShellOnce(command);
        }
    }

    public synchronized String getForegroundWindow() throws IOException {
        String focus = shell("dumpsys window windows | grep mCurrentFocus");
        if (focus != null && !focus.trim().isEmpty() && !focus.contains("mCurrentFocus=null")) {
            return focus.trim();
        }
        return shell("dumpsys activity activities | grep mResumedActivity").trim();
    }

    public synchronized void sendKeys(int... keyCodes) throws IOException {
        if (keyCodes == null || keyCodes.length == 0) return;
        StringBuilder sb = new StringBuilder("k");
        for (int code : keyCodes) {
            sb.append(' ').append(code);
        }
        if (trySendFastLine(sb.toString())) {
            return;
        }
        StringBuilder fallback = new StringBuilder("input keyevent");
        for (int code : keyCodes) {
            fallback.append(' ').append(code);
        }
        shell(fallback.toString());
    }

    public synchronized void sendText(String rawText, boolean submitEnter) throws IOException {
        String clean = sanitizeTextForFastInput(rawText);
        if (clean.isEmpty()) {
            if (submitEnter) sendKeys(66);
            return;
        }
        String prefix = submitEnter ? "s " : "t ";
        if (trySendFastLine(prefix + clean)) {
            return;
        }
        String escaped = escapeForInputText(rawText);
        if (escaped.isEmpty()) {
            if (submitEnter) sendKeys(66);
            return;
        }
        shell("input text " + escaped + (submitEnter ? "; input keyevent 66" : ""));
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            if (connectedHost == null) {
                throw new IOException("Not connected to TV");
            }
            connect(connectedHost);
        }
    }

    private void ensureFastInputQuietly() {
        try {
            ensureFastInput();
        } catch (IOException ignored) {
            fastLocalId = 0;
            fastRemoteId = 0;
        }
    }

    private boolean trySendFastLine(String line) throws IOException {
        ensureConnected();
        if (tvinDexBytes == null) return false;
        if (fastLocalId == 0 || fastRemoteId == 0) {
            ensureFastInputQuietly();
        }
        if (fastLocalId == 0 || fastRemoteId == 0) {
            return false;
        }
        try {
            return writeAndAwaitFastAck(line);
        } catch (IOException e) {
            fastLocalId = 0;
            fastRemoteId = 0;
            // Reconnect once if socket dropped, then retry fast stream before falling back.
            String host = this.connectedHost;
            if (host != null) {
                connect(host);
                if (fastLocalId != 0 && fastRemoteId != 0) {
                    try {
                        return writeAndAwaitFastAck(line);
                    } catch (IOException ignored) {
                        fastLocalId = 0;
                        fastRemoteId = 0;
                    }
                }
            }
            return false;
        }
    }

    private static String getMd5(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void ensureFastInput() throws IOException {
        if (tvinDexBytes == null || tvinDexBytes.length == 0) return;

        // Verify hash instead of raw size to guarantee matching class definitions.
        String expectedMd5 = getMd5(tvinDexBytes);
        String md5Out = runShellOnce("md5sum " + REMOTE_DEX_PATH + " 2>/dev/null").trim();
        if (!md5Out.startsWith(expectedMd5)) {
            String b64 = Base64.encodeToString(tvinDexBytes, Base64.NO_WRAP);
            runShellOnce("echo '" + b64 + "' | base64 -d > " + REMOTE_DEX_PATH + " && chmod 644 " + REMOTE_DEX_PATH);
        }

        int localId = nextLocalId.getAndIncrement();
        byte[] service = ("shell:CLASSPATH=" + REMOTE_DEX_PATH + " app_process / com.tvremote.tvinput.TvInputServer\0")
                .getBytes(StandardCharsets.UTF_8);
        sendPacket(A_OPEN, localId, 0, service);

        int remoteId = 0;
        StringBuilder startup = new StringBuilder();
        while (true) {
            Packet pkt = readPacket();
            if (pkt.arg1 != localId) {
                handleForeignPacket(pkt, localId);
                continue;
            }
            if (pkt.command == A_OKAY) {
                remoteId = pkt.arg0;
            } else if (pkt.command == A_WRTE) {
                if (remoteId == 0) remoteId = pkt.arg0;
                sendPacket(A_OKAY, localId, remoteId, null);
                if (pkt.data != null && pkt.data.length > 0) {
                    startup.append(new String(pkt.data, StandardCharsets.UTF_8));
                    if (startup.indexOf("READY") >= 0) {
                        this.fastLocalId = localId;
                        this.fastRemoteId = remoteId;
                        return;
                    }
                }
            } else if (pkt.command == A_CLSE) {
                if (remoteId == 0) remoteId = pkt.arg0;
                sendPacket(A_CLSE, localId, remoteId, null);
                throw new IOException("TvInputServer exited early: " + startup);
            }
        }
    }

    private boolean writeAndAwaitFastAck(String line) throws IOException {
        byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
        sendPacket(A_WRTE, fastLocalId, fastRemoteId, payload);

        boolean acked = false;
        StringBuilder reply = new StringBuilder();
        while (true) {
            Packet pkt = readPacket();
            if (pkt.arg1 != fastLocalId) {
                handleForeignPacket(pkt, fastLocalId);
                continue;
            }
            if (pkt.command == A_OKAY) {
                acked = true;
                if (reply.indexOf("\n") >= 0) return true;
            } else if (pkt.command == A_WRTE) {
                sendPacket(A_OKAY, fastLocalId, fastRemoteId, null);
                if (pkt.data != null && pkt.data.length > 0) {
                    reply.append(new String(pkt.data, StandardCharsets.UTF_8));
                }
                if (reply.indexOf("\n") >= 0) return true;
            } else if (pkt.command == A_CLSE) {
                sendPacket(A_CLSE, fastLocalId, fastRemoteId, null);
                fastLocalId = 0;
                fastRemoteId = 0;
                return acked && reply.indexOf("OK") >= 0;
            }
        }
    }

    private static String sanitizeTextForFastInput(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c == ' ') {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            lastSpace = false;
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Fallback shell escaping for `/system/bin/sh -c "input text ..."`.
     */
    public static String escapeForInputText(String raw) {
        if (raw == null) return "";
        StringBuilder body = new StringBuilder();
        boolean lastWasSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace && body.length() > 0) {
                    body.append("%s");
                    lastWasSpace = true;
                }
                continue;
            }
            lastWasSpace = false;
            if (c < 0x20 || c > 0x7E || c == '%') {
                continue;
            }
            if (c == '\'') {
                body.append("'\\''");
            } else {
                body.append(c);
            }
        }
        while (body.length() >= 2 && body.substring(body.length() - 2).equals("%s")) {
            body.setLength(body.length() - 2);
        }
        if (body.length() == 0) return "";
        return "'" + body + "'";
    }

    private String runShellOnce(String command) throws IOException {
        int localId = nextLocalId.getAndIncrement();
        byte[] service = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
        sendPacket(A_OPEN, localId, 0, service);

        int remoteId = 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Packet pkt = readPacket();
            if (pkt.arg1 != localId) {
                handleForeignPacket(pkt, localId);
                continue;
            }
            if (pkt.command == A_OKAY) {
                remoteId = pkt.arg0;
            } else if (pkt.command == A_WRTE) {
                if (remoteId == 0) remoteId = pkt.arg0;
                if (pkt.data != null && pkt.data.length > 0) {
                    output.write(pkt.data);
                }
                sendPacket(A_OKAY, localId, remoteId, null);
            } else if (pkt.command == A_CLSE) {
                if (remoteId == 0) remoteId = pkt.arg0;
                sendPacket(A_CLSE, localId, remoteId, null);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
    }

    private void handleForeignPacket(Packet pkt, int currentLocalId) throws IOException {
        if (pkt.arg1 == fastLocalId && fastLocalId != 0) {
            if (pkt.command == A_CLSE) {
                sendPacket(A_CLSE, fastLocalId, fastRemoteId, null);
                fastLocalId = 0;
                fastRemoteId = 0;
            } else if (pkt.command == A_WRTE && pkt.arg0 != 0) {
                sendPacket(A_OKAY, fastLocalId, pkt.arg0, null);
            }
            return;
        }
        if (pkt.command == A_WRTE && pkt.arg0 != 0) {
            sendPacket(A_OKAY, currentLocalId, pkt.arg0, null);
        }
    }

    private void sendPacket(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int crc = 0;
        if (data != null) {
            for (byte b : data) {
                crc += (b & 0xFF);
            }
        }
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(len);
        header.putInt(crc);
        header.putInt(command ^ 0xFFFFFFFF);
        out.write(header.array());
        if (len > 0) {
            out.write(data);
        }
        out.flush();
    }

    private Packet readPacket() throws IOException {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = bb.getInt();
        int arg0 = bb.getInt();
        int arg1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // crc
        bb.getInt(); // magic
        if (len < 0 || len > MAX_DATA * 2) {
            throw new IOException("Invalid ADB payload size: " + len);
        }
        byte[] data = new byte[len];
        if (len > 0) {
            in.readFully(data);
        }
        return new Packet(cmd, arg0, arg1, data);
    }

    public synchronized void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
        fastLocalId = 0;
        fastRemoteId = 0;
    }

    private static final class Packet {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
