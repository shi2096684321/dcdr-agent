# V3.1 F-43 代码执行沙箱: OpenSandbox Sandbox Server
#
# 背景与三个"为什么"(都是实测踩出来的, 换环境前请先读):
#
# 1) 为什么装完 PyPI 包还要补 3 个文件:
#    PyPI 的 wheel 与 sdist 都缺少 services/fast_sandbox/generated 模块
#    (__init__.py / fastpath_pb2.py / fastpath_pb2_grpc.py, 共约 51KB, protobuf/gRPC 生成产物),
#    服务端启动即 ModuleNotFoundError。官方 Dockerfile 是整仓源码安装, 故无此问题。
#    这里用官方源码里的同一份文件补齐(仅补齐, 不替换其余实现)。
#
# 2) 为什么走 jsDelivr CDN 而不是 GitHub:
#    本网络环境下 github.com 与 raw.githubusercontent.com 均不可达(宿主/容器实测超时),
#    而 cdn.jsdelivr.net 可达; 若你的网络能直连 GitHub, 把 OPENSANDBOX_RAW_BASE 换成
#    https://raw.githubusercontent.com/opensandbox-group/OpenSandbox/main 即可。
#
# 3) 为什么关掉 pip 进度条:
#    Docker 20.10.8 的旧 seccomp profile 拦截 glibc 的 clone3, 容器内 pthread_create 会失败
#    ("can't start new thread"), 而 pip 的 rich 进度条恰好依赖线程。
#    运行容器另需 security_opt: seccomp=unconfined(见 docker-compose.yml, 与 postgres 同一原因)。
FROM python:3.10-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_INPUT=1 \
    PIP_PROGRESS_BAR=off \
    SANDBOX_CONFIG_PATH=/etc/opensandbox/config.toml

# 版本需与 pom.xml 的 com.alibaba.opensandbox:sandbox 保持一致
ARG OPENSANDBOX_SERVER_VERSION=1.1.0
ARG OPENSANDBOX_RAW_BASE=https://cdn.jsdelivr.net/gh/opensandbox-group/OpenSandbox@main

RUN pip install --no-cache-dir --progress-bar off --no-input \
        "opensandbox-server==${OPENSANDBOX_SERVER_VERSION}" \
    && python -c "import urllib.request as u, pathlib; base='${OPENSANDBOX_RAW_BASE}/server/opensandbox_server/services/fast_sandbox/generated/'; d=pathlib.Path('/usr/local/lib/python3.10/site-packages/opensandbox_server/services/fast_sandbox/generated'); d.mkdir(parents=True, exist_ok=True); [u.urlretrieve(base+f, d/f) for f in ('__init__.py','fastpath_pb2.py','fastpath_pb2_grpc.py')]; print('generated patched:', sorted(p.name for p in d.iterdir()))" \
    && python -c "import opensandbox_server.services.fast_sandbox.generated as g; print('generated-module-ok:', g.__file__)"
# 注: 此处不验证 import opensandbox_server.main —— 该模块在 import 期即执行 load_config(),
# 而配置文件与 API Key 要到下一层/运行时才就位, 构建期 import 会误报 ValidationError。
# 完整启动验证放在 compose up 之后看日志。

COPY sandbox.toml /etc/opensandbox/config.toml

EXPOSE 8080

ENTRYPOINT ["opensandbox-server"]
CMD ["--config", "/etc/opensandbox/config.toml"]
