# The release artifact is the Spring Boot jar run on a jlink-produced Java 25
# runtime (ADR 0012). The base is distroless *java-base*: the OS libraries a JVM
# needs and nothing else -- no shell, no package manager, uid 65532, and a root
# filesystem the deployment mounts read-only.
#
# Build it from a tree that has already produced the jar:
#   ./mvnw -DskipTests -Dskip.ui.verify=true package
#
# The jar is architecture-independent, so one buildx invocation produces both
# published platforms; only this stage's runtime is platform-specific, and it is
# built for the target platform rather than cross-linked.

# --- The runtime and the staged jar ------------------------------------------
FROM eclipse-temurin:25-jdk AS runtime

# An explicit module set rather than the whole JDK -- jlink is the reason
# java-base can stay small. It is deliberately NOT derived with `jdeps`: a Spring
# application resolves much of its module graph reflectively, so a derived set is
# one that fails at runtime instead of at build time. This one is chosen
# generously and proven by the smoke test, which boots the image this produces.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.localedata,jdk.management,jdk.management.jfr,jdk.naming.dns,jdk.net,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.xml.dom,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /javaruntime

# The jar carries the Maven version in its name, which the entrypoint cannot.
# Renaming it here also asserts there is exactly one: a stale jar left in target/
# would otherwise be a coin toss rather than a failed build.
COPY target/skills-gateway-server-*.jar /staging/
RUN set -eu; \
    count=$(find /staging -maxdepth 1 -name '*.jar' | wc -l); \
    [ "$count" = 1 ] || { echo "expected exactly one jar in target/, found $count"; exit 1; }; \
    mkdir -p /app; \
    mv /staging/*.jar /app/skills-gateway-server.jar

# Distroless has no shell; stage an empty, nonroot-owned data directory here.
RUN mkdir -p /data && chown 65532:65532 /data

# --- The image ---------------------------------------------------------------
FROM gcr.io/distroless/java-base-debian12:nonroot

COPY --from=runtime /javaruntime /opt/java
COPY --from=runtime /app/skills-gateway-server.jar /app/skills-gateway-server.jar
# Writable git storage for the nonroot user (uid 65532); override with
# SKILLSGATEWAY_DATADIR and mount a volume in real deployments.
COPY --from=runtime --chown=65532:65532 /data /data
ENV SKILLSGATEWAY_DATADIR=/data

WORKDIR /app
USER nonroot
EXPOSE 8080

# MaxRAMPercentage is set on purpose. The JVM's default ceiling is a quarter of
# the container's limit, where the native binary this replaces took up to 80%;
# leaving the default would have made the heap available to JGit while it packs a
# large upstream repository a third of what the previous artifact had. 75% keeps
# the headroom the measurements in ADR 0012 were taken against.
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/skills-gateway-server.jar"]
