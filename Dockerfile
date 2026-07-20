FROM hlyn3voy1ie4dwn74t.xuanyuan.run/eclipse-temurin:26-jre

# 时区
ENV TZ=Asia/Shanghai

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone

# 替换为国内 apt 源并安装 ffmpeg
RUN set -eux; \
    . /etc/os-release; \
    echo "当前系统: ${ID} ${VERSION_ID}"; \
    if [ "${ID}" = "ubuntu" ]; then \
        if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
            sed -i \
                -e 's|http://archive.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                -e 's|http://security.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                -e 's|https://archive.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                -e 's|https://security.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                /etc/apt/sources.list.d/ubuntu.sources; \
        fi; \
        if [ -f /etc/apt/sources.list ]; then \
            sed -i \
                -e 's|http://archive.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                -e 's|http://security.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                -e 's|https://archive.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                -e 's|https://security.ubuntu.com/ubuntu/|https://mirrors.aliyun.com/ubuntu/|g' \
                /etc/apt/sources.list; \
        fi; \
    elif [ "${ID}" = "debian" ]; then \
        if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
            sed -i \
                -e 's|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' \
                -e 's|http://security.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g' \
                -e 's|https://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' \
                -e 's|https://security.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g' \
                /etc/apt/sources.list.d/debian.sources; \
        fi; \
        if [ -f /etc/apt/sources.list ]; then \
            sed -i \
                -e 's|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' \
                -e 's|http://security.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g' \
                -e 's|https://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' \
                -e 's|https://security.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g' \
                /etc/apt/sources.list; \
        fi; \
    else \
        echo "不支持的基础系统: ${ID}"; \
        exit 1; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends ffmpeg; \
    ffmpeg -version; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 创建非 root 用户
RUN groupadd -r appuser \
    && useradd -r -g appuser appuser

# 本地先打好 jar 包：mvn package -DskipTests
COPY --chown=appuser:appuser cognitive-profile-backend-*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseContainerSupport", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]