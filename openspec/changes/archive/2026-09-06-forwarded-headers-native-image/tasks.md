# Tasks: forwarded-headers-native-image

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0163 (forwarded scheme and host honoured only when configured,
      by one setting meaning the same on the JVM and the native image; off by
      default; an absolute redirect URI by environment) to
      `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0163 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Investigate option 1 from the issue (SVC_GW_0163, evidence.md)

- [x] 2.1 Read Spring Boot 4.1.1's `ServletWebServerConfiguration`,
      `TomcatWebServerFactoryCustomizer` and `CloudPlatform`: the filter is
      behind `@ConditionalOnProperty`; the valve is installed by a runtime
      customizer; `native` is deduced on Kubernetes and ECS when unset
- [x] 2.2 Build the native binary and probe every posture against it, before
      and after the fix

## 3. Backend (SVC_GW_0163)

- [x] 3.1 `ForwardedHeadersConfig` in `dev.skillsgateway.server.config`,
      `@Requirements({"GW_0163"})`: an unconditional
      `FilterRegistrationBean<ForwardedHeaderFilter>` enabled at runtime when
      `server.forward-headers-strategy` is `framework`
- [x] 3.2 `application.yaml`: `redirect-uri:
      ${SGW_OIDC_REDIRECT_URI:{baseUrl}/login/oauth2/code/idp}`

## 4. Tests (never weakening an existing SVC test)

- [x] 4.1 `AbstractForwardedHeadersTest` and one `ForwardedHeaders*Tests` class per
      posture, `@SVCs({"SVC_GW_0163"})`: a real server per
      posture — unset ignores the headers, `framework` and `native` honour
      them, exactly one filter is registered under `framework`, the absolute
      URI is immune to the headers
- [x] 4.2 `PackagingTests`: the chart names the strategy, gates it, and passes
      the redirect URI through; `application.yaml` reads the variable

## 5. Chart, CI and local loop

- [x] 5.1 `values.yaml`: `forwardHeadersStrategy: native`, `oidc.redirectUri`
- [x] 5.2 `deployment.yaml`: `SERVER_FORWARDHEADERSSTRATEGY` through the
      `skills-gateway.forwardHeadersStrategy` gate; `SGW_OIDC_REDIRECT_URI`
      when set
- [x] 5.3 `native.yml`: the smoke test runs the container with `framework` and
      asserts the forwarded scheme and host reach the authorization redirect

## 6. Documentation (same PR)

- [x] 6.1 `guides/deploying-without-kubernetes.md`: "Running behind a proxy"
      rewritten — the three values, whom each believes, the native-image note,
      the absolute redirect URI; examples switched to `native`
- [x] 6.2 `guides/deploying-on-kubernetes.md`: the `extraEnv` warning becomes
      the `forwardHeadersStrategy` section, with the instruction to remove the
      old entry
- [x] 6.3 `reference/configuration.md`: "Forwarded headers" section, summary
      row, `SGW_OIDC_REDIRECT_URI` in the OIDC table
- [x] 6.4 `guides/identity-providers.md`: the redirect-URI row and the pointer

## 7. Gates and archive

- [x] 7.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [x] 7.2 `openspec/changes/forwarded-headers-native-image/evidence.md`
- [x] 7.3 Archive the change as the PR's final commit
