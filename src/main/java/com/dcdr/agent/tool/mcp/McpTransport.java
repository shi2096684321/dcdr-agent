package com.dcdr.agent.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP stdio 传输层 - 基于 JSON-RPC 2.0 的进程间通信。
 * <p>
 * 负责在 Host(本应用) 与 MCP Server(独立子进程) 之间通过标准输入输出交换消息:
 * 请求(request)带自增 id, 按 id 阻塞等待对应响应(response);
 * 通知(notification, 无 id)与服务器主动发来的请求(如 sampling)暂以回调忽略处理。
 * </p>
 * <p>纯 JDK 实现, 不依赖任何 MCP SDK, 便于离线编译。</p>
 */
@Slf4j
public class McpTransport implements AutoCloseable {

    private final Process process;
    private final ObjectMapper mapper;
    private final BufferedReader reader;
    private final OutputStream writer;
    private final AtomicLong idGen = new AtomicLong(1);
    private final Map<Long, PendingResponse> pending = new ConcurrentHashMap<>();
    private final Thread readerThread;
    private volatile boolean closed = false;

    public McpTransport(ProcessBuilder processBuilder, ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        this.process = processBuilder.start();
        this.writer = process.getOutputStream();
        this.reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        this.readerThread = new Thread(this::pump, "mcp-transport-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        log.info("MCP 传输层已启动子进程: {}", processBuilder.command());
    }

    /** 发送一个请求并阻塞等待对应响应 */
    public synchronized Map<String, Object> request(String method, Object params, long timeoutMs)
            throws Exception {
        long id = idGen.getAndIncrement();
        Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.put("params", params);
        }
        PendingResponse pr = new PendingResponse();
        pending.put(id, pr);
        try {
            String line = mapper.writeValueAsString(msg);
            log.debug("→ MCP request: {}", line);
            writer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            writer.flush();
            return pr.await(timeoutMs);
        } finally {
            pending.remove(id);
        }
    }

    /** 发送通知(无需响应) */
    public synchronized void notify(String method, Object params) throws Exception {
        Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) {
            msg.put("params", params);
        }
        String line = mapper.writeValueAsString(msg);
        log.debug("→ MCP notify: {}", line);
        writer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        writer.flush();
    }

    private void pump() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                handleLine(line);
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("MCP 传输层读取子进程输出异常", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLine(String line) {
        try {
            Map<String, Object> msg = mapper.readValue(line, Map.class);
            log.debug("← MCP message: {}", line);
            Object idObj = msg.get("id");
            if (idObj instanceof Number) {
                long id = ((Number) idObj).longValue();
                PendingResponse pr = pending.get(id);
                if (pr != null) {
                    pr.complete(msg);
                }
            } else {
                // 通知或服务器主动请求: 仅记录, 不阻塞
                log.debug("MCP 收到通知/服务端请求: {}", msg.get("method"));
            }
        } catch (Exception e) {
            log.warn("MCP 消息解析失败: {}", line, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            notify("exit", null);
        } catch (Exception ignored) {
            // 忽略
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // 忽略
        }
        process.destroyForcibly();
        if (readerThread != null) {
            readerThread.interrupt();
        }
        log.info("MCP 传输层已关闭子进程");
    }

    /** 检查底层进程是否还活着 */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** 取进程错误流(便于排查 Server 启动失败原因) */
    public InputStream getErrorStream() {
        return process.getErrorStream();
    }

    private static final class PendingResponse {
        private Map<String, Object> result;
        private boolean done;

        synchronized Map<String, Object> await(long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!done && System.currentTimeMillis() < deadline) {
                wait(Math.max(50, timeoutMs / 10));
            }
            if (!done) {
                throw new IllegalStateException("MCP 请求超时 (" + timeoutMs + "ms)");
            }
            if (result.containsKey("error")) {
                Object err = result.get("error");
                throw new IllegalStateException("MCP 错误响应: " + err);
            }
            return result;
        }

        synchronized void complete(Map<String, Object> msg) {
            this.result = msg;
            this.done = true;
            notifyAll();
        }
    }
}
