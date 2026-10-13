package com.dcdr.agent.cluster;

import java.net.InetAddress;
import java.util.concurrent.ThreadLocalRandom;

/**
 * V3.1 F-46 实例标识。
 * <p>
 * 多实例部署下, 集群租约锁需要回答"谁持有锁", 故每个实例必须有一个稳定可区分的标识。
 * <ul>
 *   <li>显式配置 {@code agent.v3.cluster.instance-id} 时直接使用(推荐: app-1 / app-2);</li>
 *   <li>留空则自动生成"主机名-随机后缀" —— 容器环境下主机名通常就是容器 ID 前缀,
 *       已足够区分; 随机后缀用于兜住"同一主机跑多个 jar"的场景。</li>
 * </ul>
 * <p>
 * 为什么不用 {@code server.port} 之类的值: 它不保证唯一(多主机同端口), 且与部署方式耦合。
 */
public final class ClusterIdentity {

    private final String id;

    private ClusterIdentity(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ClusterIdentity resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return new ClusterIdentity(configured.trim());
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        if (host == null || host.isBlank()) host = "unknown-host";
        return new ClusterIdentity(host + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xffffff)));
    }

    @Override
    public String toString() {
        return id;
    }
}
