## ADDED Requirements

### Requirement: GW_VETTING_0035
The system SHALL implement GW_VETTING_0035.

The portal SHALL carry an administrator-only Vetting page that governs the vetting
chain across the estate: the chain as it applies to a marketplace with no override
(the mode, the vetter order and each vetter's on/off state), every marketplace that
departs from that default and what it departs in, and the controls to change either.
The page SHALL be reachable from the Governance group of the sidebar for a session
holding the administrative role and SHALL NOT advertise itself to any other session,
which SHALL be refused with a stated reason rather than an empty page. The controls
SHALL be the same ones the marketplace card carries, scoped globally, and the state
they report SHALL be resolved by the gateway rather than recomposed in the browser.

#### Scenario: SVC_GW_VETTING_0035
The system SHALL pass SVC_GW_VETTING_0035.

### Requirement: GW_VETTING_0036
The system SHALL implement GW_VETTING_0036.

The gateway SHALL let an administrator remove a marketplace's chain-mode, vetter-order
or vetter-enablement override, so that the marketplace resolves from the global setting
or the default again, and SHALL record each removal on the audit ledger with the acting
identity, the scope and the note. Removing an override SHALL be distinct from writing the
default value as an override: after the removal the marketplace's effective setting SHALL
report its source as the global setting or the default, never as the marketplace. Removing
an override that does not exist SHALL leave the estate and the ledger unchanged rather than
failing.

#### Scenario: SVC_GW_VETTING_0036
The system SHALL pass SVC_GW_VETTING_0036.

### Requirement: GW_VETTING_0037
The system SHALL implement GW_VETTING_0037.

The gateway SHALL accept one chain change — a mode, an order, a vetter's enablement, or the
removal of overrides — addressed to several named marketplaces as a single administrator-only
request, and the portal SHALL show which marketplaces are affected and what each would change
from and to before it is applied. A request whose shape is invalid SHALL be refused whole, with
nothing written and nothing audited. Otherwise each named marketplace SHALL be attempted
independently and the response SHALL report the outcome per marketplace, distinguishing a request
in which every marketplace succeeded from one in which any did not; the portal SHALL NOT report a
partial failure as a success. Every marketplace the request changed SHALL receive its own audit
ledger entry carrying the request's shared reason and a correlation identifier common to the act,
so an auditor can read the entries as one deliberate estate-wide change rather than a loop.

#### Scenario: SVC_GW_VETTING_0037
The system SHALL pass SVC_GW_VETTING_0037.
