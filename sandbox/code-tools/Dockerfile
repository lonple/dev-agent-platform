# dap-sandbox-webdev：JDK + 构建工具 + code-tools 管理面
# 基础镜像用本机已有 temurin tag，避免经 registry-mirrors 拉失败
FROM eclipse-temurin:21.0.12_8-jdk

USER root
RUN set -eux; \
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
      sed -i 's|http://archive.ubuntu.com/ubuntu/|https://mirrors.tuna.tsinghua.edu.cn/ubuntu/|g' /etc/apt/sources.list.d/ubuntu.sources; \
      sed -i 's|http://security.ubuntu.com/ubuntu/|https://mirrors.tuna.tsinghua.edu.cn/ubuntu/|g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    if [ -f /etc/apt/sources.list ]; then \
      sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' /etc/apt/sources.list; \
      sed -i 's|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' /etc/apt/sources.list; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      git ca-certificates ripgrep curl unzip zip \
      maven \
      python3; \
    curl -fsSL https://deb.nodesource.com/setup_24.x | bash -; \
    apt-get install -y --no-install-recommends nodejs; \
    npm install -g pnpm@9; \
    rm -rf /var/lib/apt/lists/*; \
    java -version; \
    mvn -v; \
    node -v; \
    npm -v; \
    git --version; \
    rg --version

# 由构建脚本复制本机 Gradle 发行版（跨平台 JAR + Linux 启动器），避免重复下载。
COPY .build-tools/gradle/ /opt/gradle/
RUN set -eux; \
    chmod +x /opt/gradle/bin/gradle; \
    ln -s /opt/gradle/bin/gradle /usr/local/bin/gradle; \
    apt-get update; apt-get install -y --no-install-recommends python3-venv; \
    python3 -m venv /opt/uv-env; \
    /opt/uv-env/bin/pip install --no-cache-dir uv; \
    ln -s /opt/uv-env/bin/uv /usr/local/bin/uv; \
    python3 -c 'import sys; assert sys.version_info >= (3, 14, 2)'; \
    npx --yes playwright@1.65.1 install --with-deps chromium
ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright

WORKDIR /opt/code-tools
COPY build/libs/dap-code-tools-*.jar /opt/code-tools/app.jar
COPY .build-tools/dap-scripts/ /opt/dap-scripts/
ENV WORKSPACE_ROOT=/workspace \
    CODE_TOOLS_PORT=9100
EXPOSE 9100
VOLUME ["/workspace", "/root/.m2", "/root/.npm"]
# 显式 --server.port，避免沙箱 env 里的 SERVER_PORT=8080（业务口）抢占管理面
ENTRYPOINT ["java","-jar","/opt/code-tools/app.jar","--server.port=9100"]
