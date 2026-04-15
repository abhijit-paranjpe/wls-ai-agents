FROM oraclelinux:10-slim

# Install dependencies for downloading and extracting tarballs
RUN microdnf install -y curl tar gzip \
	&& microdnf clean all

# Download and install Oracle JDK 25
ENV JDK_TARBALL_URL=https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz
ENV JAVA_HOME=/opt/jdk-25
ENV CHAT_CONTEXT_DIR=/tmp/wls-chat-contexts
RUN mkdir -p /opt \
	&& curl -L "$JDK_TARBALL_URL" -o /tmp/jdk.tar.gz \
	&& tar -xzf /tmp/jdk.tar.gz -C /opt \
	&& rm /tmp/jdk.tar.gz \
	&& mv /opt/jdk-25* "$JAVA_HOME"
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN mkdir -p /app /app/server /app/runtime/config "$CHAT_CONTEXT_DIR"

WORKDIR /app/server

# Copy the prebuilt application artifacts from the root distribution directory
COPY dist/wls-agents.jar ./wls-agents.jar
COPY dist/libs ./libs

EXPOSE 8080

VOLUME ["/tmp/wls-chat-contexts"]

CMD ["java", "-jar", "wls-agents.jar"]
