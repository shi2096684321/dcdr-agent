package com.example.agent.agent;

import com.example.agent.dto.ChatChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 活跃对话注册表 - 按 sessionId 持有进行中的对话句柄，用于支持"中断会话"。
 * <p>
 * 关键点:
 * 1. 客户端断开 SSE 连接或调用 stop 接口时, 通过 {@link ChatHandle#cancelUpstream()}
 *    取消上游 WebClient 订阅, 真正中断底层 HTTP 请求(大模型调用)。
 * 2. 主动停止时通过 {@link ChatHandle#interrupt(FluxSink)} 向前端下发 interrupted 信号。
 */
@Slf4j
@Component
public class ActiveChatRegistry {

    private final ConcurrentHashMap<String, ChatHandle> handles = new ConcurrentHashMap<>();

    /**
     * 注册一个活跃对话。若同一 session 已有进行中的对话, 先取消旧的(避免孤儿流)。
     */
    public void register(String sessionId, ChatHandle handle) {
        ChatHandle old = handles.put(sessionId, handle);
        if (old != null && !old.isFinished()) {
            log.warn("会话 {} 已有进行中的对话, 取消旧对话", sessionId);
            old.cancelUpstream();
        }
    }

    /** 注销(对话正常结束 / 客户端断开时调用) */
    public void unregister(String sessionId) {
        handles.remove(sessionId);
    }

    /**
     * 主动中断会话。返回是否真的有进行中的对话被中断。
     */
    public boolean stop(String sessionId) {
        ChatHandle handle = handles.get(sessionId);
        if (handle == null || handle.isFinished()) {
            return false;
        }
        handle.interrupt();
        return true;
    }

    /** 查询会话是否正在对话中 */
    public boolean isActive(String sessionId) {
        ChatHandle handle = handles.get(sessionId);
        return handle != null && !handle.isFinished();
    }

    /**
     * 单个对话句柄: 同时持有下游 SSE 的 FluxSink 与上游模型调用的 Disposable。
     */
    public static class ChatHandle {
        private final FluxSink<ChatChunk> sink;
        private final Disposable upstream;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        public ChatHandle(FluxSink<ChatChunk> sink, Disposable upstream) {
            this.sink = sink;
            this.upstream = upstream;
        }

        /** 仅取消上游(客户端断开连接时使用, 不再向已关闭的通道写数据) */
        public void cancelUpstream() {
            if (finished.compareAndSet(false, true)) {
                if (!upstream.isDisposed()) {
                    upstream.dispose();
                }
            }
        }

        /** 主动中断: 取消上游 + 向前端下发 interrupted 信号并结束流 */
        public void interrupt() {
            if (finished.compareAndSet(false, true)) {
                if (!upstream.isDisposed()) {
                    upstream.dispose();
                }
                try {
                    sink.next(ChatChunk.interrupted());
                    sink.complete();
                } catch (Exception e) {
                    log.warn("下发中断信号失败(连接可能已关闭)", e);
                }
            }
        }

        /** 对话正常完成, 标记结束(避免后续 stop/cancel 重复处理) */
        public void markFinished() {
            finished.set(true);
        }

        public boolean isFinished() {
            return finished.get();
        }
    }
}
