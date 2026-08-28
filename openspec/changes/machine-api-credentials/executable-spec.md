# Executable spec — machine-api-credentials

Task 2.1. Every scenario below is a named test with concrete inputs and concrete
expected status codes. Task 2.3: **spec approval: not obtained (autonomous run)** —
this document is the artifact reviewed after the fact, and confidence is claimed
correspondingly lower.

Task 2.2 — setup plan: **no new dependencies.** Everything needed (Spring
Security filter chains, `PathPatternRequestMatcher`, JdbcClient, MockMvc,
AssertJ, the Arconia PostgreSQL dev service) is already present. No new tool is
installed. Files added are Java sources under
`src/main/java/dev/skillsgateway/server/auth/` and `.../persistence/`, tests
under `src/test/java/dev/skillsgateway/server/`, and documentation pages under
`docs/manual/`.

## Vocabulary

- **machine credential** — an `access_tokens` row whose `api_scopes` is non-empty.
- **fetch token** — a row whose `api_scopes` is empty (every token that exists today).
- `Bearer <secret>` is the only scheme the machine chain accepts.

## Group 0 — the enumerating allowlist test (prerequisite)

| Test | Input | Expected |
| --- | --- | --- |
| `every_mapped_api_method_is_classified` | the running application's `RequestMappingHandlerMapping` route table, filtered to `/api/**` | every `METHOD /pattern` appears exactly once in `MachineApiRegistry` — either under a named scope or on the unreachable list. A route in neither fails the test. |
| `the_registry_names_no_route_the_application_does_not_serve` | the registry | every registry entry corresponds to a live route; a stale entry fails. |
| `no_route_is_both_reachable_and_unreachable` | the registry | the two sets are disjoint. |

Negative control (task 0.2): a throwaway `GET /api/throwaway-guard-probe` is
added; `every_mapped_api_method_is_classified` must fail naming it; it is then
removed and both outputs recorded in `evidence.md`.

## Group 3 — persistence

| Test | Input | Expected |
| --- | --- | --- |
| `a_row_with_no_api_scopes_grants_no_api_reach` | `AccessToken` with `apiScopes = null` | `apiScopeList()` is empty; `permitsApiScope(any)` is false; `machineCredential()` is false. (SVC_GW_0126) |
| `the_three_empty_list_defaults_differ` | a token with all three lists empty | fetch → every marketplace; push → nothing; API → nothing. (SVC_GW_0126) |

## Group 4 — ledger actor type

| Test | Expected |
| --- | --- |
| `a_machine_credential_entry_carries_actor_type_machine_and_its_name` | `actor_type='machine'`, `principal` = the credential's principal. (SVC_GW_0128) |
| `the_ledger_separates_actors_without_string_parsing` | `FetchLogRepository.listByActorType('machine'\|'human'\|'system')` returns disjoint sets, no join to `access_tokens`. (SVC_GW_0128) |
| `a_ledger_row_survives_deletion_of_the_credential_it_names` | after `DELETE FROM access_tokens WHERE id = ?`, the row still reports `actor_type='machine'`. (SVC_GW_0128) |
| `machine_api_entries_carry_the_token_id` | `token_id` = the credential's id. (SVC_GW_0128) |
| `no_machine_entry_names_the_provisioning_human` | ledger entries produced by the credential name the credential's principal, never the admin who minted it; the admin appears only on `token-created`. (SVC_GW_0128) |
| `system_actors_are_declared_not_inferred` | reconciler/scheduler/waiver entries carry `actor_type='system'`. (SVC_GW_0128) |

## Group 5 — issuance

| Test | Input | Expected |
| --- | --- | --- |
| `an_unknown_api_scope_is_refused_at_issue_time` | `apiScopes=["marketplaces:regsiter"]` | HTTP 422 (SVC_GW_0126) |
| `there_is_no_wildcard_api_scope` | `"*"`, `"all"` | HTTP 422 each; empty list → HTTP 422 (a machine credential needs at least one scope) (SVC_GW_0126) |
| `issuance_without_an_expiry_is_refused` | no `expiresAt` | HTTP 422, never defaulted (SVC_GW_0131) |
| `issuance_beyond_the_cap_is_refused_not_clamped` | `expiresAt = now + 400d` with the built-in 90-day cap | HTTP 422 (SVC_GW_0131) |
| `a_session_derived_credential_cannot_hold_api_scope` | `TokenService.createMachine(..., sessionDerived=true)` | `InvalidTokenRequestException` (SVC_GW_0127) |

## Group 6 — the negative guarantee

All against `/api/marketplaces` (and the whole reachable table where stated).

| Test | Credential | Expected |
| --- | --- | --- |
| `an_every_marketplace_fetch_token_reaches_no_api_endpoint` | fetch scopes empty, api empty | 401 on every `/api/**` route (SVC_GW_0127) |
| `a_named_fetch_scope_token_reaches_no_api_endpoint` | fetch `["m"]` | 401 (SVC_GW_0127) |
| `a_push_only_token_reaches_no_api_endpoint` | push `["m"]` | 401 (SVC_GW_0127) |
| `a_session_derived_credential_reaches_no_api_endpoint` | session-derived | 401 (SVC_GW_0127) |
| `an_api_only_credential_is_refused_by_the_facade_and_the_publication_chain` | api `["marketplaces:read"]` | git clone fails; `/publish/**` 401 (SVC_GW_0127) |
| `an_api_only_credential_with_an_empty_fetch_list_reaches_no_marketplace` | api scope, fetch empty | `permitsMarketplace(any)` false; facade clone fails (SVC_GW_0127) — **6.5a, asserts the opposite of current behaviour** |
| `an_ordinary_fetch_token_with_an_empty_fetch_list_still_reaches_every_marketplace` | api empty, fetch empty | `permitsMarketplace(any)` true; clone succeeds (SVC_GW_0064) — regression guard |
| `a_revoked_or_expired_machine_credential_is_refused` | revoked; expired | 401 each (SVC_GW_0127, SVC_GW_0131) |
| `a_request_carrying_both_bearer_and_cookie_is_refused` | valid machine bearer + `Cookie: JSESSIONID=x` | 401 (SVC_GW_0127) |
| `a_bearer_request_creates_no_session_and_sets_no_cookie` | valid machine bearer | no `Set-Cookie`, `request.getSession(false)` null (SVC_GW_0127) |
| `a_browser_session_without_a_bearer_header_reaches_the_api_as_before` | oidcLogin | 200 (SVC_GW_0011) |
| `garbage_empty_and_facade_bearer_values_are_indistinguishable_401s` | `Bearer zzz`, `Bearer `, `Bearer <valid fetch PAT>` | 401 with byte-identical bodies (SVC_GW_0127) |
| `a_facade_token_presented_as_basic_does_not_authenticate_the_api` | HTTP Basic with a valid PAT | 401 (SVC_GW_0127) |
| `dev_insecure_auth_does_not_open_the_bearer_path` | `dev-insecure-auth=true`, `Bearer zzz` | 401 (SVC_GW_0127) |

## Group 8 — scopes and the allowlist

| Test | Expected |
| --- | --- |
| `scope_enforcement_holds_with_role_enforcement_disabled` | `roles.enabled=false` (the default): a credential holding `marketplaces:read` gets 200 on `GET /api/marketplaces` and 403 on `GET /api/estate` (SVC_GW_0129) |
| `each_scope_reaches_its_own_endpoints_and_no_others` | parameterised over the whole scope table: for each scope S, every route of S is not 403, and every route of every other scope is 403 (SVC_GW_0129) |
| `no_scope_implies_another` | `policy:write` alone → 403 on `GET /api/policy/rules`; `marketplaces:register` alone → 403 on `GET /api/marketplaces` (SVC_GW_0129) |
| `scopes_compose_additively` | `{marketplaces:read, estate:read}` reaches exactly the union (SVC_GW_0129) |
| `every_scope_at_once_plus_admin_still_cannot_reach_the_unreachable_table` | one assertion per unreachable route → 403 (SVC_GW_0129) |
| `machine_provisioning_endpoints_are_unreachable_by_machine` | all `/api/tokens/**` → 403 (SVC_GW_0129) |
| `a_revet_is_reachable_and_publishes_nothing` | `vetting:run` → 2xx; snapshot state unchanged (SVC_GW_0129) |
| `retention_candidates_is_reachable_while_evaluate_is_not` | 200 / 403; evaluate's soft-delete is why (SVC_GW_0129) |
| `a_machine_read_of_roles_writes_exactly_one_ledger_entry` | `actor_type='machine'`, event `roles-read` (SVC_GW_0128) |
| `a_human_read_of_roles_writes_exactly_one_ledger_entry` | `actor_type='human'`, IdP subject (SVC_GW_0128) |
| `a_machine_read_of_the_audit_ledger_writes_no_entry` | ledger size unchanged (SVC_GW_0128) |
| `an_unauthorized_roles_read_writes_no_entry` | 403 → no `roles-read` entry (SVC_GW_0128) |

## Group 9 — roles

| Test | Expected |
| --- | --- |
| `a_machine_credential_derives_no_claim_role` | `ClaimRoleMapper.rolesFrom(machineAuth)` is empty (SVC_GW_0130) |
| `authority_is_the_intersection_of_scope_and_role` | roles on: scope without role → 403; role without scope → 403 (SVC_GW_0130) |
| `a_machine_credential_cannot_reach_the_grants_api` | `POST /api/roles` → 403 with every scope (SVC_GW_0130) |
| `estate_grants_can_declare_a_machine_principal` | reconcile grants it; ledger `actor_type='system'`, actor `config-reconciler` (SVC_GW_0130) |
| `minting_a_machine_credential_requires_admin_even_with_roles_disabled` | `roles.enabled=false`, non-admin session → 403 on create, admin list, admin revoke (SVC_GW_0130) |

## Group 10 — lifecycle

| Test | Expected |
| --- | --- |
| `rotation_preserves_every_api_scope_value_and_the_expiry_deadline` | asserted per scope value; old secret dead before the new one is returned (SVC_GW_0131) |
| `an_admin_lists_and_revokes_another_principals_machine_credential` | admin 200/204; non-admin 403 (SVC_GW_0131) |
| `revocation_takes_effect_on_the_next_request` | 200 then 401, no sleep (SVC_GW_0131) |
| `the_built_in_cap_applies_when_max_ttl_is_unset` | `skills-gateway.tokens.max-ttl` unset, `expiresAt = now + 100y` → 422 (SVC_GW_0131) |

## Invariants that must survive

- SVC_GW_0011 — the browser session still reaches `/api/**` unchanged.
- SVC_GW_0012 — the facade still requires a valid PAT.
- SVC_GW_0064 — an empty fetch scope list on a non-machine token still grants every marketplace.
- SVC_GW_0102 — push scope semantics unchanged.
- SVC_GW_0104 — session-derived credentials unchanged, and now provably cannot hold API scope.
- No existing SVC test weakened or deleted (task 13.3).
