## 1. Schema

- [x] 1.1 Sweep every migration for columns whose `CHECK` enumerates a closed value set; the
      six in issue #136 plus `marketplaces.origin`, `marketplaces.push_policy`,
      `vetting_runs.outcome`, `vetting_findings.severity` and `role_grants.role`
- [x] 1.2 Add the eleven `CREATE TYPE <singular table>_<column> AS ENUM (...)` statements to
      `V1__init.sql`, with the naming rule and the two PostgreSQL evolution constraints in a
      header comment
- [x] 1.3 Convert the eleven columns, keeping the three `DEFAULT`s and the table-level
      `marketplaces_hosted_is_on_demand` constraint intact

## 2. Repositories

- [x] 2.1 Cast on write in `MarketplaceRepository`, `SnapshotRepository`,
      `AuditSinkRepository`, `VettingRepository`, `WaiverRepository` and
      `RoleGrantRepository` (`:state::snapshot_state`); leave the mappers alone, the driver
      returns a `String`
- [x] 2.2 Add `@Requirements({"GW_0125"})` to every method that writes an enumerated value,
      so the annotation set is exactly the enum write paths

## 3. Requirements

- [x] 3.1 Add GW_0125 to `docs/reqstool/requirements.yml` and SVC_GW_0125 to
      `docs/reqstool/software_verification_cases.yml`

## 4. Tests

- [x] 4.1 `NativeEnumColumnTests`: the declared type and the exact label set of all eleven
      columns
- [x] 4.2 A round trip per type through the repository that owns it, every value of the set
- [x] 4.3 A refusal per column of a value outside the set, and proof that the marketplace
      constraint reading two enum columns still fires
- [x] 4.4 Add `@SVCs({"SVC_GW_0125"})` to each test method from 4.1–4.3
- [x] 4.5 Prove red before green: drop one cast (round trips fail), then revert one column to
      `TEXT ... CHECK` (the type and refusal cases fail); restore and re-run green

## 5. Conventions and gates

- [x] 5.1 Write the convention and its two evolution constraints into
      `.claude/skills/code-conventions`
- [x] 5.2 Run every gate fresh and record `evidence.md`
