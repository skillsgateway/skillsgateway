# Packages the prebuilt GraalVM native binary (./mvnw -Pnative -DskipTests native:compile).
# The binary is mostly-static (--static-nolibc): only glibc is dynamic, matching
# distroless *base*.

# Distroless has no shell; stage an empty, nonroot-owned data directory here.
FROM busybox:1.37 AS dirs
RUN mkdir -p /data && chown 65532:65532 /data

FROM gcr.io/distroless/base-debian12:nonroot

COPY target/skills-gateway /app/skills-gateway
# Writable git storage for the nonroot user (uid 65532); override with
# SKILLSGATEWAY_DATADIR and mount a volume in real deployments.
COPY --from=dirs --chown=65532:65532 /data /data
ENV SKILLSGATEWAY_DATADIR=/data

WORKDIR /app
USER nonroot
EXPOSE 8080

ENTRYPOINT ["/app/skills-gateway"]
