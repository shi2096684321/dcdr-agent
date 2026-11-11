package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.SystemPromptProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemPromptResolver} 的分层解析测试。
 *
 * <p>锁住三件容易悄悄坏掉的事：
 * <ol>
 *   <li><b>L1 可从外置文件读取</b>：人设从 yml 挪到 {@code prompts/identity.md} 后，
 *       若解析器仍只读内联字段，改 md 文件就完全没反应，且不报任何错；</li>
 *   <li><b>内联优先</b>：yml 里一旦写了内联文本，对应文件必须被忽略 —— 这是既有语义，
 *       外置化不能顺手把它改掉；</li>
 *   <li><b>生图不拼 L1</b>：图像模型不支持 system 消息，人设会被当作画面描述前缀拼进去。
 *       L1 从一百字扩到四段后，这个误注入的代价明显变大，故显式锁定。</li>
 * </ol>
 */
class SystemPromptResolverTest {

    /** 测试专用人设文件，避免断言依赖生产 prompts/identity.md 的具体措辞 */
    private static final String TEST_L1 = "TEST-L1 人设：由测试资源文件提供，用于验证 default-location 外置机制生效。";
    private static final String TEST_L1_PATH = "classpath:prompts/test-identity.md";
    /** 确定不存在的路径，用于构造「L1 为空」的场景（否则会回退读到真实的 identity.md） */
    private static final String MISSING_PATH = "classpath:prompts/__not_exists__.md";

    private static SystemPromptResolver resolver(String defaultPrompt, String defaultLocation,
                                                 String chat, String chatLocation,
                                                 String textToImage, String imageToImage) {
        SystemPromptProperties.Scenes scenes = new SystemPromptProperties.Scenes();
        scenes.setChat(chat);
        scenes.setChatLocation(chatLocation);
        scenes.setTextToImage(textToImage);
        scenes.setImageToImage(imageToImage);

        SystemPromptProperties props = new SystemPromptProperties();
        props.setDefaultPrompt(defaultPrompt);
        props.setDefaultLocation(defaultLocation);
        props.setScenes(scenes);

        return new SystemPromptResolver(props, new PromptTemplateLoader());
    }

    @Nested
    @DisplayName("L1 外置与内联优先级")
    class L1Resolution {

        @Test
        @DisplayName("default-prompt 为空时从 default-location 文件读取")
        void loadsL1FromFile() {
            SystemPromptResolver r = resolver(null, TEST_L1_PATH, "", "", null, null);
            assertEquals(TEST_L1, r.resolveDefaultOnly(), "L1 应来自外置文件");
        }

        @Test
        @DisplayName("内联 default-prompt 非空时覆盖文件（外置化不改变既有优先级）")
        void inlineOverridesFile() {
            SystemPromptResolver r = resolver("内联人设", TEST_L1_PATH, "", "", null, null);
            assertEquals("内联人设", r.resolveDefaultOnly(), "内联必须优先于文件");
        }

        @Test
        @DisplayName("L1 与 L2 都不可用时返回 null（调用方据此不注入 system 消息）")
        void nullWhenNothingAvailable() {
            SystemPromptResolver r = resolver(null, MISSING_PATH, "", "", null, null);
            assertNull(r.resolveChat());
        }
    }

    @Nested
    @DisplayName("聊天场景合并顺序")
    class ChatMerge {

        @Test
        @DisplayName("L1 在前、L2 在后，中间用空行分隔")
        void l1BeforeL2() {
            SystemPromptResolver r = resolver("L1", TEST_L1_PATH, "L2", "", null, null);
            assertEquals("L1\n\nL2", r.resolveChat(),
                    "更具体的场景级内容应靠后，且两段指令之间要有空行分隔");
        }

        @Test
        @DisplayName("L1 为空时只返回 L2")
        void onlyL2WhenL1Empty() {
            SystemPromptResolver r = resolver(null, MISSING_PATH, "L2", "", null, null);
            assertEquals("L2", r.resolveChat());
        }

        @Test
        @DisplayName("生产配置：identity.md 能被读到，且排在 chat.md 之前")
        void productionIdentityFileIsReachable() {
            // 直接按 application.yml 的值构造，防止有人改了 yml 路径却没人发现
            SystemPromptResolver r = resolver(null, "classpath:prompts/identity.md", null, null, null, null);
            String chat = r.resolveChat();

            assertNotNull(chat, "L1 或 L2 至少要有一份，否则 system 消息根本不会注入");
            assertTrue(chat.contains("dcdr"), "L1 人设应由 prompts/identity.md 提供");
            assertTrue(chat.contains("你的任务是"), "L2 应由 prompts/chat.md 提供");
            assertTrue(chat.indexOf("【身份层级】") < chat.indexOf("你的任务是"),
                    "L1 人设必须排在 chat.md 之前");
        }
    }

    @Nested
    @DisplayName("生图场景不拼 L1")
    class ImageScenes {

        @Test
        @DisplayName("L1 有值时不注入文生图（否则人设会被画进图里）")
        void textToImageIgnoresL1() {
            SystemPromptResolver r = resolver(TEST_L1, TEST_L1_PATH, "", "", null, null);
            assertNull(r.resolveTextToImage(), "文生图不得携带 L1 人设");
        }

        @Test
        @DisplayName("图生图同样不拼 L1")
        void imageToImageIgnoresL1() {
            SystemPromptResolver r = resolver(TEST_L1, TEST_L1_PATH, "", "", null, null);
            assertNull(r.resolveImageToImage(), "图生图不得携带 L1 人设");
        }

        @Test
        @DisplayName("生图只取 L2 对应场景的增强文本")
        void imageScenesUseL2Only() {
            SystemPromptResolver r = resolver(TEST_L1, TEST_L1_PATH, "", "", "图像增强", "图生图增强");
            assertEquals("图像增强", r.resolveTextToImage());
            assertEquals("图生图增强", r.resolveImageToImage());
        }
    }
}
