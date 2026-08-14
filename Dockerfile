# Packages the prebuilt GraalVM native binary (./mvnw -Pnative package).
# The binary is dynamically linked (glibc), so distroless *base* — not static.
FROM gcr.io/distroless/base-debian12:nonroot

COPY target/skills-gateway /app/skills-gateway

WORKDIR /app
USER nonroot
EXPOSE 8080

ENTRYPOINT ["/app/skills-gateway"]
