package com.dcdr.agent.tool.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeGuard} 单元测试(V3.1 F-43 代码沙箱静态审查)。
 *
 * <p>纯单元测试: 无状态静态方法, 零外部依赖, 不启 Spring 上下文。
 *
 * <p><b>为什么它必须被测试</b>: 这个类有<b>两个方向</b>的失效风险, 且都很严重 ——
 * <ul>
 *   <li><b>漏放(true negative)</b>: 危险调用没被拦 → 沙箱静态防线形同虚设</li>
 *   <li><b>误杀(false positive)</b>: 正常代码被拒 → 模型"算个数"都做不到,
 *       该工具的唯一价值场景被毁掉(类注释里明确警告过这一点)</li>
 * </ul>
 * 所以本测试<b>两个方向都要断言</b>: 既验证危险模式被拒, 也验证正常代码放行。
 * 仅测"能拦住"是不完整的, 会掩盖误杀。
 *
 * <p>注: 该静态审查可被字符串拼接 / getattr 反射绕过, 是<b>辅助防线</b>(真正边界在沙箱侧),
 * 本测试不试图证明它"安全", 只锁定它当前的拦截契约。
 */
class CodeGuardTest {

    // ==================================================================
    //  拦截方向: 危险调用必须被拒绝
    // ==================================================================

    @ParameterizedTest(name = "[{index}] 应被拒绝")
    @ValueSource(strings = {
            "import os\nos.system('ls')",                      // 执行系统命令
            "import os\nos.popen('ls')",                       // 执行系统命令
            "import os\nos.execv('/bin/sh', [])",              // 创建/替换进程
            "import os\nos.kill(1, 9)",                        // 发送信号
            "import subprocess\nsubprocess.run(['ls'])",       // subprocess 模块
            "import ctypes",                                   // 加载本地库
            "import socket",                                   // 网络套接字
            "import requests\nrequests.get('http://x')",       // 网络请求
            "__import__('os')",                                // 动态导入
            "import importlib",                                // 动态导入
            "eval('1+1')",                                     // 动态执行
            "exec('x=1')",                                     // 动态执行
            "compile('x=1', '<s>', 'exec')",                   // 动态编译
            "import pickle\npickle.loads(b'x')",               // 反序列化
            "import shutil\nshutil.rmtree('/tmp/x')",          // 删除文件
            "import os\nos.remove('f')",                       // 删除文件
            "import os\nos.chmod('f', 0o777)"                  // 修改权限
    })
    @DisplayName("危险调用被拒绝, 并返回中文拒绝原因")
    void rejectsDangerousCalls(String code) {
        String reason = CodeGuard.check("python", code);
        assertNotNull(reason, "应拒绝该代码但被放行: " + code);
        assertTrue(reason.contains("禁止"), "拒绝原因应为可读的中文说明, 实际: " + reason);
    }

    // ==================================================================
    //  放行方向: 正常代码不得误杀(与上组同等重要)
    // ==================================================================

    @ParameterizedTest(name = "[{index}] 应被放行")
    @ValueSource(strings = {
            "print(sum([1, 2, 3]))",
            "import math\nprint(math.sqrt(16))",
            "print(2 ** 10)",
            "data = [3, 1, 2]\nprint(sorted(data))",
            "print('eval is a word')",                         // 字符串里含 eval 但不是调用
            "socket_timeout = 5\nprint(socket_timeout)"        // 变量名含 socket 但不越词边界
    })
    @DisplayName("正常计算/数据处理代码被放行(不误杀)")
    void allowsSafeCode(String code) {
        assertNull(CodeGuard.check("python", code),
                "正常代码被误杀, 会让模型连算数都做不到: " + code);
    }

    // ==================================================================
    //  边界契约
    // ==================================================================

    @Test
    @DisplayName("空 / null 代码直接放行")
    void blankCodePasses() {
        assertNull(CodeGuard.check("python", null));
        assertNull(CodeGuard.check("python", ""));
        assertNull(CodeGuard.check("python", "   "));
    }

    @Test
    @DisplayName("非 python 语言不在此处审查(交由执行层拒绝)")
    void nonPythonLanguageNotCheckedHere() {
        assertNull(CodeGuard.check("java", "Runtime.getRuntime().exec(\"ls\")"));
        assertNull(CodeGuard.check("javascript", "require('child_process').exec('ls')"));
    }

    @Test
    @DisplayName("语言名大小写不敏感")
    void languageIsCaseInsensitive() {
        assertNotNull(CodeGuard.check("Python", "import os\nos.system('ls')"));
        assertNotNull(CodeGuard.check("PYTHON", "import socket"));
        assertNull(CodeGuard.check("PYTHON", "print(1)"));
    }
}
