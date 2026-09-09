package top.mcocet.miniloginqueue.util;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft Server List Ping (MSLP) 客户端
 * <p>
 * 使用 Minecraft 原生的服务器列表查询协议（1.7+ 状态协议）直接探测目标服务器，
 * 用于获取真实在线人数、最大容量、版本与延迟，不依赖任何代理通道。
 * <p>
 * 数据帧格式：
 * <pre>
 *  客户端 → Handshake  (packet 0x00: protocol/address/port/nextState=1)
 *  客户端 → StatusReq  (packet 0x00)
 *  服务器 → StatusResp (packet 0x00: JSON {version, players, description, favicon})
 * </pre>
 */
public final class MslpPinger {

    private MslpPinger() {
    }

    /**
     * 直连探测结果
     */
    public static final class Result {
        private final boolean success;
        private final int onlinePlayers;
        private final int maxPlayers;
        private final int latencyMs;
        private final String versionName;
        private final String error;

        private Result(boolean success, int onlinePlayers, int maxPlayers, int latencyMs,
                       String versionName, String error) {
            this.success = success;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.latencyMs = latencyMs;
            this.versionName = versionName;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public int getOnlinePlayers() {
            return onlinePlayers;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public int getLatencyMs() {
            return latencyMs;
        }

        public String getVersionName() {
            return versionName;
        }

        public String getError() {
            return error;
        }
    }

    /**
     * 向指定地址发起一次服务器列表探测（阻塞调用，必须在异步线程执行）
     *
     * @param host      服务器 IP 或域名
     * @param port      服务器游戏端口
     * @param timeoutMs 连接与读取超时（毫秒）
     */
    public static Result ping(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 1. Handshake 包（next state = 1 状态查询）
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(buffer);
            packet.writeByte(0x00); // 包 ID: Handshake
            writeVarInt(packet, -1); // 协议版本 -1 = 任意版本
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(packet, hostBytes.length);
            packet.write(hostBytes);
            packet.writeShort(port);
            writeVarInt(packet, 1); // next state: 1 = status
            packet.flush();
            byte[] handshake = buffer.toByteArray();
            writeVarInt(out, handshake.length);
            out.write(handshake);

            // 2. Status Request 包
            out.writeByte(0x01); // 包长度
            out.writeByte(0x00); // 包 ID: Status Request
            out.flush();

            // 3. 读取响应：长度 | 包ID | JSON长度 | JSON
            int frameLength = readVarInt(in);
            if (frameLength < 1 || frameLength > 1048576) {
                return failure(start, "异常的数据帧长度: " + frameLength);
            }
            int packetId = readVarInt(in);
            if (packetId != 0) {
                return failure(start, "意外的响应包 ID: " + packetId);
            }
            int jsonLength = readVarInt(in);
            if (jsonLength <= 0 || jsonLength > frameLength) {
                return failure(start, "异常的 JSON 长度: " + jsonLength);
            }
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            Integer online = extractInt(json, "online");
            Integer max = extractInt(json, "max");
            if (online == null || max == null) {
                return failure(start, "响应缺少 players 数据: " + json);
            }
            int latency = (int) (System.currentTimeMillis() - start);
            return new Result(true, online, max, latency, extractString(json, "name"), null);
        } catch (IOException e) {
            return failure(start, e.getMessage());
        }
    }

    private static Result failure(long start, String error) {
        return new Result(false, 0, 0, (int) (System.currentTimeMillis() - start), null, error);
    }

    // ==================== 协议读写 ====================

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        // 标准 Minecraft VarInt 编码（支持负数，最多 5 字节）
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int i = 0; i < 5; i++) {
            int read = in.readUnsignedByte();
            result |= (read & 0x7F) << (7 * i);
            if ((read & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("VarInt 超过 5 字节");
    }

    // ==================== 简易 JSON 提取 ====================
    // 仅提取 "key": 之后的 JSON 数值/字符串。响应中的键均为裸形态，
    // 而 description 等文本内容中的引号会被 JSON 转义，不会误命中键。

    private static Integer extractInt(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIndex + marker.length());
        if (colon < 0) {
            return null;
        }
        int index = colon + 1;
        while (index < json.length() && (json.charAt(index) == ' ' || json.charAt(index) == '\t')) {
            index++;
        }
        boolean negative = false;
        if (index < json.length() && json.charAt(index) == '-') {
            negative = true;
            index++;
        }
        int numberStart = index;
        while (index < json.length() && Character.isDigit(json.charAt(index))) {
            index++;
        }
        if (numberStart == index) {
            return null;
        }
        int value = 0;
        try {
            value = Integer.parseInt(json.substring(numberStart, index));
        } catch (NumberFormatException e) {
            return null;
        }
        return negative ? -value : value;
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIndex + marker.length());
        if (colon < 0) {
            return null;
        }
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                // 简化处理：版本名等展示文本一般不含复杂转义
                value.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }
}
