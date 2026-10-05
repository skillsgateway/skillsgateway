# Proposal: clearer waive action and multi-select waivers

## Why

Two gaps remain after the actionable-findings work (#500), reported in #597:

1. The visible waive label ("Waive finding", "Waive all 4 locations") does not
   name the rule it accepts, and the waiver form never says how many findings
   the chosen scope will cover. "Every concealment-instruction finding in this
   snapshot" gives no count, and the submit button reads only "Record waiver".
2. A group waiver covers one rule at a time. A reviewer clearing a snapshot
   with findings from several rules fills in the same form, with the same
   justification and expiry, once per group.

## What Changes

- **Label names the rule:** a blocking group's waive button reads
  "Waive <rule>…" for a single location and "Waive <rule> at N locations…" for
  several. The trailing ellipsis keeps its usual meaning: the button opens a
  form.
- **Coverage before submit:** the waiver form states how many findings the
  selected scope covers in this snapshot, and the submit button carries that
  count ("Record waiver for 4 findings"). A path scope reaches later snapshots
  too, so its count is labelled as this snapshot's count.
- **Multi-select:** each blocking, unwaived finding group gets a checkbox.
  Selecting one or more opens a single form for one justification and one
  expiry, with the same 90-day bound. That form lists the selected rules and
  the total number of findings it will cover. Submitting records one group
  waiver per selected group through the existing API, then reports how many
  were recorded and names any group that was refused.
- Portal only. There is no API, backend or schema change, and nothing
  **BREAKING**.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `vetting-waivers`: GW_VETTING_0044 — The portal presents each finding group
  once with a waive action that says what it covers, is revised so that the
  label names the rule and the form states its coverage. GW_VETTING_0061 —
  Several blocking finding groups are waived in one action, is added.

## Impact

- Portal: `src/main/frontend/src/components/vetting-report.tsx`, which holds
  the group row, the waive form and a new selection bar with its form. Its
  component test, story and e2e spec change with it.
- Requirements: `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml`, where GW_VETTING_0044 and
  SVC_GW_VETTING_0044 are revised to 0.5.0 and GW_VETTING_0061 and
  SVC_GW_VETTING_0061 are added.
- Docs: the waiver section of the portal manual.
- Stop rule: this adds no backend package, estate object, role, sweep or
  configuration leaf. The bulk action posts the same request the single
  action posts, so the server's waiver rules (GW_VETTING_0042 — A waiver on a
  finding group covers exactly that group) still bound each waiver.
