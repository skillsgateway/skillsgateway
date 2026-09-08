# Evidence: forwarded-headers-native-image

Touches the web surface's authentication flow (the redirect URI the OIDC
login is built on) and decides whom the gateway believes about a request's
origin, so old-coder discipline applies: RED was observed on the real native
binary before GREEN, the negative postures are asserted, and no existing SVC
test was edited.

Implementation commit: `ddf9dc16ec6a96216e25e98c3bc83014ba54d644`. The only
edits after it are this report and the archive move.

## Spec ↔ test mapping

| Requirement | Verifies | Test |
| --- | --- | --- |
| GW_AUTH_0029 — Proxy-reported scheme and host are honoured only when configured, identically on every packaging | headers ignored when unset; honoured under `framework` and `native`; exactly one filter registered under `framework`; the absolute redirect URI immune to headers | `ForwardedHeadersUnsetTests`, `ForwardedHeadersFrameworkTests`, `ForwardedHeadersNativeTests`, `ForwardedHeadersRedirectUriTests` on `AbstractForwardedHeadersTest` (SVC_GW_AUTH_0029), 5 tests across 4 real-server contexts |
| GW_RELEASE_0001 — Container distribution | the chart names the strategy, gates it, passes the redirect URI through; `application.yaml` reads the variable | `PackagingTests.chartNamesTheForwardedHeaderStrategyAndPassesTheRedirectUriThrough` (SVC_GW_RELEASE_0001) |

"Identically on every packaging" cannot be asserted by a JVM test — on the
JVM, Boot's own registration works — so it is asserted two other ways: the
native probe below, and `native.yml`'s smoke test, which now runs the
container with `framework` and checks the authorization redirect.

## Option 1 investigated: what Spring Boot 4.1.1 actually does

Read from the published source jars (`spring-boot-web-server`,
`spring-boot-tomcat`, `spring-boot` 4.1.1), not from memory:

- `ServletWebServerConfiguration.forwardedHeaderFilter()` —
  `@ConditionalOnProperty(name = "server.forward-headers-strategy",
  havingValue = "framework")` plus
  `@ConditionalOnMissingFilterBean(ForwardedHeaderFilter.class)`. Boot's
  native-image reference lists exactly this under the closed-world
  restrictions: *"Properties that change if a bean is created are not
  supported (for example, `@ConditionalOnProperty` and `.enabled`
  properties)."*
- `TomcatWebServerFactoryCustomizer.customizeRemoteIpValve()` reads
  `serverProperties.getForwardHeadersStrategy()` in `customize()`, at
  runtime, and installs `RemoteIpValve` with `X-Forwarded-Proto`,
  `X-Forwarded-Host`, `X-Forwarded-Port` and internal proxies
  `192.168.0.0/16, 172.16.0.0/12, 169.254.0.0/16, fc00::/7, 10.0.0.0/8,
  100.64.0.0/10, 127.0.0.0/8, fe80::/10, ::1/128`. The customizer's
  auto-configuration is gated on classes and web-application type only.
- `getOrDeduceUseForwardHeaders()`: with the strategy unset, returns
  `CloudPlatform.getActive(environment).isUsingForwardHeaders()`, true for
  every detected platform but `NONE`. `CloudPlatform.AWS_ECS` (since 4.0)
  detects `AWS_EXECUTION_ENV` starting with `AWS_ECS`; `KUBERNETES` detects
  `KUBERNETES_SERVICE_HOST` + `KUBERNETES_SERVICE_PORT`.

So option 1 should hold, and the issue's deployment — ECS Fargate with the
variable set to `framework` — should have been failing for two compounding
reasons: the explicit value switched the deduction off, and the value itself
was inert. Both were then confirmed on the binary.

## RED then GREEN on the native binary

Two binaries built with the project's own profile (`./mvnw -Pnative
-DskipTests -Dskip.ui.verify=true native:compile`, GraalVM CE 25.2.4, about
2 min 20 s each), run against PostgreSQL 18 in Docker with
`SKILLSGATEWAY_ROLES_ADMINS_0=probe` and the placeholder identity provider.
The probe: `GET /oauth2/authorization/idp`, once plain and once with
`X-Forwarded-Proto: https` and `X-Forwarded-Host: gateway.example.com`, and
the `redirect_uri` parameter of the `Location` it answers with.

**RED** — `ForwardedHeadersConfig.java` moved out of the tree, otherwise this
branch:

```console
[red-native] env: SERVER_FORWARDHEADERSSTRATEGY=native
[red-native] plain request        -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[red-native] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
[red-framework] env: SERVER_FORWARDHEADERSSTRATEGY=framework
[red-framework] plain request        -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[red-framework] with X-Forwarded-*   -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[red-unset-ecs] env: AWS_EXECUTION_ENV=AWS_ECS_FARGATE
[red-unset-ecs] plain request        -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[red-unset-ecs] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
```

The issue, reproduced: `framework` is ignored on the pre-fix image. `native`
works, and ECS with the variable unset works, on the same pre-fix image.

**GREEN** — the file restored, the same probes plus the remaining postures:

```console
[green-unset] env:
[green-unset] plain request        -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[green-unset] with X-Forwarded-*   -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[green-none] env: SERVER_FORWARDHEADERSSTRATEGY=none
[green-none] with X-Forwarded-*   -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[green-native] env: SERVER_FORWARDHEADERSSTRATEGY=native
[green-native] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
[green-framework] env: SERVER_FORWARDHEADERSSTRATEGY=framework
[green-framework] plain request        -> redirect_uri = http://localhost:18080/login/oauth2/code/idp
[green-framework] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
[green-unset-ecs] env: AWS_EXECUTION_ENV=AWS_ECS_FARGATE
[green-unset-ecs] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
[green-framework-ecs] env: AWS_EXECUTION_ENV=AWS_ECS_FARGATE SERVER_FORWARDHEADERSSTRATEGY=framework
[green-framework-ecs] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
[green-unset-k8s] env: KUBERNETES_SERVICE_HOST=10.0.0.1 KUBERNETES_SERVICE_PORT=443
[green-unset-k8s] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
[green-absolute] env: SGW_OIDC_REDIRECT_URI=https://gateway.example.com/login/oauth2/code/idp
[green-absolute] plain request        -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
[green-absolute] with X-Forwarded-*   -> redirect_uri = https://gateway.example.com/login/oauth2/code/idp
```

Every posture behaves as the docs now say, and the JVM suite's four contexts
assert the same four lines on the jar.

After the servlet-only condition was added to `ForwardedHeadersConfig` (a
context without a web server has no `ServerProperties` to read, which
`ConditionalWriteFidelityTests` found), the binary was rebuilt from the
committed main sources and `unset`, `native` and `framework` re-probed with
identical results.

What was **not** verified: a real ALB or ingress controller in front of the
binary. The probes connect from loopback, which is inside Tomcat's default
internal-proxy ranges, so they show `native` honouring a private-range peer
and say nothing about a proxy outside those ranges — which is the case the
docs route to `framework` or to widening `internal-proxies`.

## Chart

No `helm` on the build host; linted and rendered through the `alpine/helm`
image instead. `helm lint` passes; the rendered Deployment carries
`SERVER_FORWARDHEADERSSTRATEGY: "native"` and, when `oidc.redirectUri` is
set, `SGW_OIDC_REDIRECT_URI`; `--set forwardHeadersStrategy=bogus` fails the
render with the message naming the three accepted values.

## Gates

One final fresh run after the last code edit, in this order. An earlier run
of the same six gates was interrupted (not by a failure) and is disregarded.
The only red seen during the work: `ConditionalWriteFidelityTests` failing to
start its non-web context before the servlet-only condition existed, and one
Storybook flake (`adoption.stories.tsx` failed to import while Vite's optimizer
was still bundling) that did not recur.

### `./mvnw clean verify`

```console
$ ./mvnw clean verify
[INFO] Tests run: 502, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 280 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  04:56 min
```

### `(cd src/main/frontend && pnpm test:stories)`

```console
$ (cd src/main/frontend && pnpm test:stories)
 ✓ |storybook (chromium)| src/pages/tokens.stories.tsx (1 test) 478ms
 ✓ |storybook (chromium)| src/components/markdown-view.stories.tsx (2 tests) 672ms
 ✓ |storybook (chromium)| src/pages/adoption.stories.tsx (3 tests) 406ms

 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### `(cd src/main/frontend && pnpm e2e)`

```console
$ (cd src/main/frontend && pnpm e2e)
  ✓  11 [chromium] › e2e/portal.spec.ts:535:1 › setup_wizard_composes_origin_derived_commands_and_holds_show_once (1.5s)
  ✓  12 [chromium] › e2e/portal.spec.ts:581:1 › preview_pane_shows_tree_inert_skill_md_and_diff_vs_served (5.9s)
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (655ms)

  13 passed (46.6s)
```

### `reqstool status local -p docs/reqstool`

```console
$ reqstool status local -p docs/reqstool
  GW_INGEST_0027             skills-gateway
  GW_VETTING_0030             skills-gateway
  GW_AUTH_0029             skills-gateway

INCOMPLETE (0)
156/156 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```console
$ openspec validate --all --strict
✓ change/forwarded-headers-native-image
…
✓ spec/virtual-catalog
Totals: 31 passed, 0 failed (31 items)
```

### `mkdocs build --strict`

```console
$ mkdocs build --strict
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: …/site
INFO    -  Documentation built in 1.11 seconds
```
