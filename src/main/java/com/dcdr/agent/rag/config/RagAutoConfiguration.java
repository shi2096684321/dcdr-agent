package com.dcdr.agent.rag.config;

import com.dcdr.agent.rag.reader.DocumentReaderRegistry;
import com.dcdr.agent.rag.reader.HtmlReader;
import com.dcdr.agent.rag.reader.PdfReader;
import com.dcdr.agent.rag.reader.PlainTextReader;
import com.dcdr.agent.rag.reader.WordReader;
import com.dcdr.agent.rag.splitter.RecursiveSplitter;
import com.dcdr.agent.rag.splitter.TextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * RAG 模块自动装配 (V2.1)。
 * <p>
 * 注册文档解析器到 {@link DocumentReaderRegistry}, 提供 {@link TextSplitter} Bean,
 * 以及 RAG 异步入库专用线程池(避免与图片/视频生成线程池互相影响)。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RagAutoConfiguration {

    private final RagProperties properties;

    /**
     * 注册所有内置文档解析器: PlainText / PDF / Word / HTML。
     * Reader 是无状态简单对象, 直接 new 即可, 无需走 Spring 容器。
     */
    @Bean
    public CommandLineRunner ragReaderRegistrar(DocumentReaderRegistry registry) {
        return args -> {
            if (!properties.isEnabled()) {
                log.info("RAG 已禁用, 跳过文档解析器注册");
                return;
            }
            registry.register(new PlainTextReader());
            registry.register(new PdfReader());
            registry.register(new WordReader());
            registry.register(new HtmlReader());
            log.info("RAG 文档解析器注册完成: {}", registry.supportedExtensions());
        };
    }

    /**
     * 文本分块器: 当前固定使用 {@link RecursiveSplitter}, 参数从配置注入。
     * 后续可按 strategy 字段扩展为工厂模式(fixed/paragraph/recursive)。
     */
    @Bean
    public TextSplitter textSplitter() {
        RagProperties.SplitterConfig sc = properties.getSplitter();
        return new RecursiveSplitter(sc.getChunkSize(), sc.getChunkOverlap());
    }

    /**
     * RAG 异步入库专用线程池。
     * <p>
     * 文档解析+向量化是 CPU+IO 混合任务, 单任务耗时数秒~数十秒(取决于文档大小与上游 API);
     * 独立于图片/视频生成线程池, 避免慢文档抢占生图线程。
     */
    @Bean("ragExecutor")
    public ThreadPoolTaskExecutor ragExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("rag-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 由 ragExecutor 派生的反应式 Scheduler, 供 DocumentService 等做 subscribeOn。
     * <p>
     * 单独暴露为 Bean 而非在 Service 内 fromExecutor 包装, 避免字段初始化顺序问题
     * (Lombok 构造器赋值晚于字段初始化, 在 Service 内 fromExecutor 会拿到 null)。
     * Scheduler 类型唯一, 注入无需 @Qualifier。
     */
    @Bean
    public Scheduler ragScheduler(ThreadPoolTaskExecutor ragExecutor) {
        return Schedulers.fromExecutor(ragExecutor);
    }
}
