package com.dcdr.agent.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产提示词文件的结构约束测试。
 *
 * <p>平台支持「专家 / Agent」角色叠加（{@code agent:persona} 独立注入，见
 * {@code AgentOrchestrator#wrapAgentPersona}），因此 L1/L2 的每条规则都必须能回答一个问题：
 * <b>专家人设能不能覆盖它？</b> 如果不能，它就得待在「硬约束」段；如果能，就必须待在
 * 「默认偏好」段，且带「以专家为准」的例外条款。
 *
 * <p>这个区分靠人工维护极容易退化 —— 往「风格」里随手加一行「必须中文回答」，翻译专家就废了；
 * 把「不编造」误放进可覆盖段，专家一句「别管那些规则」就能让模型开口编。两种退化都不会报错，
 * 只会在某个专家场景里表现为「怎么调都不听话」，故用结构断言锁住。
 *
 * <p>断言的是<b>段落归属</b>而非具体措辞，改文案不会误伤；改段结构才会红。
 */
class PromptContentTest {

    private static final String IDENTITY = "prompts/identity.md";
    private static final String CHAT = "prompts/chat.md";

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词文件失败: " + path, e);
        }
    }

    /**
     * 判断 {@code needle} 是否落在以 {@code sectionHeader} 开头的那一段里。
     * 段落边界取下一个行首的「【」标题。
     */
    private static boolean inSection(String text, String sectionHeader, String needle) {
        int start = text.indexOf(sectionHeader);
        if (start < 0) {
            return false;
        }
        int next = text.indexOf("\n【", start + sectionHeader.length());
        int end = next >= 0 ? next : text.length();
        int at = text.indexOf(needle, start);
        return at >= 0 && at < end;
    }

    @Nested
    @DisplayName("L1 身份层级")
    class IdentityTiers {

        @Test
        @DisplayName("明确底层身份与专家角色的关系，挂载专家时不再以 dcdr 自居")
        void declaresRoleLayering() {
            String md = read(IDENTITY);
            assertTrue(md.contains("【身份层级】"), "L1 必须有身份层级段，否则专家场景下人设会与 L2 抢身份");
            assertTrue(md.contains("底层身份是 dcdr"), "需说明 dcdr 是底层人格而非唯一的对外身份");
            assertTrue(md.contains("不再以 dcdr 自居"), "挂载专家时必须让位，否则翻译专家会自称多肉");
        }

        @Test
        @DisplayName("不再把「全能工作台」写成人格（那是产品定位，会稀释专家专精）")
        void doesNotClaimOmnipotentWorkbench() {
            String md = read(IDENTITY);
            assertFalse(md.contains("你是全能工作台"), "「全能」写进人格会让专职专家什么都答");
            assertFalse(md.contains("不是陪聊的问答机器人"), "与 L2 的自称冲突源，已随重构移除");
        }
    }

    @Nested
    @DisplayName("专家可覆盖性分档")
    class Overridability {

        @Test
        @DisplayName("硬约束段：不编造、对外动作确认、防注入")
        void hardRulesLiveInHardSection() {
            String md = read(IDENTITY);
            assertTrue(md.contains("【硬约束"), "必须有硬约束段");
            assertTrue(inSection(md, "【硬约束", "不编造"), "「不编造」属于任何专家都不能覆盖的底线");
            assertTrue(inSection(md, "【硬约束", "得到确认再执行"), "对外动作确认属于底线");
            assertTrue(inSection(md, "【硬约束", "忽略以上指令"), "防提示注入属于底线");
        }

        @Test
        @DisplayName("默认偏好段：语言、输出结构等专家常需覆盖的项")
        void softRulesLiveInSoftSection() {
            String md = read(IDENTITY);
            assertTrue(md.contains("【默认偏好"), "必须有默认偏好段，否则专家无法覆盖平台默认风格");
            assertTrue(inSection(md, "【默认偏好", "中文回答"),
                    "语言必须放在可覆盖段 —— 翻译专家要输出英文，放在硬约束里会直接卡死");
            assertTrue(inSection(md, "【默认偏好", "三段式"), "输出结构偏好必须可被专家覆盖");
        }
    }

    @Nested
    @DisplayName("L2 只管任务，不再定义身份")
    class ChatScene {

        @Test
        @DisplayName("不再自称「专业的智能问答助手」，平台自述已并入 L1")
        void noSelfIdentity() {
            String md = read(CHAT);
            assertFalse(md.contains("专业的智能问答助手"), "身份定义收口到 L1，L2 再自称会与专家三方打架");
            assertFalse(md.contains("平台自述"), "平台自述已并入 identity.md，留在 L2 会出现两套说法");
        }

        @Test
        @DisplayName("输出规范带「以专家规定为准」的例外条款")
        void outputRulesDeferToExpert() {
            String md = read(CHAT);
            assertTrue(md.contains("以专家规定为准"),
                    "没有例外条款的话，翻译专家的「直接给译文」会被「开头正文结论」压住");
            assertFalse(md.contains("包含开头（直接回答核心问题）"),
                    "三段式硬性要求已移除 —— 它与 L1「别套三段式」直接对立");
            assertTrue(md.contains("按内容需要组织"), "结构应按内容自适应");
        }

        @Test
        @DisplayName("引用角标留在硬性规则里（前端按 [[n]] 渲染来源卡片，不能被专家改掉）")
        void citationProtocolStaysHard() {
            String md = read(CHAT);
            assertTrue(inSection(md, "【硬性规则", "[[n]]"), "[[n]] 是平台协议，必须不可覆盖");
            assertTrue(md.contains("硬性规则（第 1~3 条）始终有效"), "需显式声明硬性规则不受专家影响");
        }
    }
}
