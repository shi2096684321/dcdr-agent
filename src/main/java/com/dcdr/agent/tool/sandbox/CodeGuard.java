package com.dcdr.agent.tool.sandbox;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 代码执行沙箱的<b>静态审查</b>(V3.1 F-43) —— 辅助防线, <b>不是主要隔离手段</b>。
 * <p>
 * <b>为什么它是辅助的</b>: 真正的边界由 OpenSandbox 侧实施 —— 独立容器、
 * {@code NetworkPolicy} 默认 DENY(禁网)、资源配额、只读根文件系统、非 root 用户。
 * 静态审查只负责把"语义明确、无正当用途"的危险调用挡在沙箱外, 降低攻击面与误用概率。
 * <p>
 * <b>它可被绕过</b>: 字符串拼接、{@code getattr} 反射、编码混淆都能规避正则匹配,
 * 因此<b>不可单独依赖</b>; 反过来也不应过度收紧 —— 误杀会让模型正常代码无法执行,
 * 把"能算数"这个唯一价值场景毁掉。故只收录语义明确的模式。
 */
public final class CodeGuard {

    /** 危险模式: 进程 / 网络 / 动态执行 / 删除类调用 */
    private static final List<Rule> PYTHON_RULES = List.of(
            new Rule("\\bos\\.system\\s*\\(", "禁止调用 os.system(执行系统命令)"),
            new Rule("\\bos\\.popen\\s*\\(", "禁止调用 os.popen(执行系统命令)"),
            new Rule("\\bos\\.(exec|spawn|fork)\\w*\\s*\\(", "禁止创建/替换进程"),
            new Rule("\\bos\\.kill\\s*\\(", "禁止向进程发送信号"),
            new Rule("\\bsubprocess\\b", "禁止使用 subprocess 模块"),
            new Rule("\\b(ctypes|cffi)\\b", "禁止加载本地库(ctypes/cffi)"),
            new Rule("\\bsocket\\b", "禁止使用网络套接字"),
            new Rule("\\b(urllib|requests|httpx|ftplib|smtplib|telnetlib|paramiko)\\b", "禁止发起网络请求"),
            new Rule("\\b__import__\\s*\\(|\\bimportlib\\b", "禁止动态导入模块"),
            new Rule("\\b(eval|exec|compile)\\s*\\(", "禁止动态执行/编译代码"),
            new Rule("\\b(pickle|marshal)\\.loads?\\s*\\(", "禁止反序列化不可信数据"),
            new Rule("\\bshutil\\.rmtree\\s*\\(|\\bos\\.(remove|unlink|rmdir|rename)\\s*\\(",
                    "禁止删除/重命名文件"),
            new Rule("\\bos\\.(chmod|chown)\\s*\\(", "禁止修改文件权限/属主")
    );

    private record Rule(Pattern pattern, String reason) {
        Rule(String regex, String reason) {
            this(Pattern.compile(regex), reason);
        }
    }

    /**
     * 审查代码。
     *
     * @param language 语言(python)
     * @param code     待执行代码
     * @return 命中危险模式时返回拒绝原因(给模型看的中文说明); 通过时返回 {@code null}
     */
    public static String check(String language, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (!"python".equalsIgnoreCase(language)) {
            // 一期仅支持 python; 其它语言不在此处审查(由执行层拒绝)
            return null;
        }
        for (Rule rule : PYTHON_RULES) {
            if (rule.pattern().matcher(code).find()) {
                return rule.reason();
            }
        }
        return null;
    }

    private CodeGuard() {
    }
}
