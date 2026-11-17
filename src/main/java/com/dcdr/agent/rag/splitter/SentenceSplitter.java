package com.dcdr.agent.rag.splitter;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 句子优先分块器。
 * <p>
 * 与默认 {@link RecursiveSplitter} 的区别只在<b>分隔符优先级</b>: 本策略先切句子
 * (。！？；!?;), 把段落/换行放到句子之后。目的是尽量不切断完整句子 ——
 * 对 FAQ、问答对、条款、散文这类"一句就是一个语义单元"的语料, 语义完整性比块长均匀更重要。
 * <p>
 * 单句超过 chunkSize 时仍会按空格、字符硬切(不会产出超长块导致 embedding 截断);
 * 合并 + overlap 逻辑与默认策略完全一致, 由父类 {@link RecursiveSplitter} 提供。
 */
@Slf4j
public class SentenceSplitter extends RecursiveSplitter {

    /** 句子边界优先; 保留"\n\n"/"\n"作为次级边界, 避免整段无标点时无法切分 */
    private static final String[] SENTENCE_SEPARATORS = {
            "。", "！", "？", "；", ". ", "! ", "? ", "; ", "\n\n", "\n", " ", ""
    };

    public SentenceSplitter(int chunkSize, int chunkOverlap) {
        super(chunkSize, chunkOverlap, SENTENCE_SEPARATORS);
    }
}
