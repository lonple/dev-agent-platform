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
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -; \
    apt-get install -y --no-install-recommends nodejs; \
    npm install -g pnpm@9; \
    rm -rf /var/lib/apt/lists/*; \
    java -version; \
    mvn -v; \
    node -v; \
    npm -v; \
    git --version; \
    rg --version

WORKDIR /opt/code-tools
COPY build/libs/dap-code-tools-*.jar /opt/code-tools/app.jar
ENV WORKSPACE_ROOT=/workspace \
    CODE_TOOLS_PORT=9100
EXPOSE 9100
VOLUME ["/workspace", "/root/.m2", "/root/.npm"]
# 显式 --server.port，避免沙箱 env 里的 SERVER_PORT=8080（业务口）抢占管理面
ENTRYPOINT ["java","-jar","/opt/code-tools/app.jar","--server.port=9100"]
