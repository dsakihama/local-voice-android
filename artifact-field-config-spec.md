# Artifact Field Configuration Spec
*PM Artifact Framework — Layer Decoupling Design*

---

## Overview

Three independent layers control how data flows into and renders within any artifact. Changing one layer never requires touching the others.

| Layer | Purpose | Changes when… |
|---|---|---|
| **Schema** | Canonical field definitions | You add/remove a tracked concept |
| **Presentation** | Display labels, visibility, order | You change audience or artifact type |
| **Mapping** | External field → schema translation | You ingest a new data source |

---

## Layer 1 — Schema (canonical truth)

Defines what fields *exist*, independent of how they're shown or named externally. This is Dean's vocabulary.

```yaml
# schema.yaml
fields:
  - id: project_name
    type: string
    description: "Official name of the initiative or workstream"
    required: true

  - id: owner
    type: string
    description: "Person accountable for delivery (single DRI)"
    required: true

  - id: status
    type: enum
    values: [green, yellow, red, on_hold, complete]
    description: "Overall health of the initiative"
    required: true

  - id: milestone
    type: string
    description: "Current or next key milestone"
    required: false

  - id: target_date
    type: date
    format: YYYY-MM-DD
    description: "Milestone or completion target date"
    required: false

  - id: blockers
    type: string
    description: "Active blockers or risks"
    required: false

  - id: last_updated
    type: date
    format: YYYY-MM-DD
    description: "Date this row was last updated"
    required: false

  - id: notes
    type: string
    description: "Freeform context or callouts"
    required: false
```

**Rules:**
- `id` values are stable and never change. Code and manifests reference `id`, never the display label.
- Adding a field → add it here first, then add a presentation entry.
- Removing a field → mark `deprecated: true` before deleting (allows artifact manifests to warn rather than break).

---

## Layer 2 — Presentation (per artifact type)

Controls how fields render in a specific artifact. One presentation config per artifact type. Multiple presentation configs can reference the same schema.

```yaml
# presentation/exec-status-dashboard.yaml
artifact_type: status_dashboard
audience: executive
label_style: formal           # formal | casual | abbreviated

fields:
  - id: project_name
    label: "Initiative"
    visible: true
    order: 1
    width: large              # large | medium | small | auto

  - id: owner
    label: "DRI"
    visible: true
    order: 2
    width: small

  - id: status
    label: "RAG"
    visible: true
    order: 3
    width: small
    render_as: badge          # badge | text | icon | pill

  - id: milestone
    label: "Next Milestone"
    visible: true
    order: 4
    width: medium

  - id: target_date
    label: "Target"
    visible: true
    order: 5
    width: small
    format: MMM D             # display format, separate from storage format

  - id: blockers
    label: "Blockers"
    visible: true
    order: 6
    width: medium

  - id: last_updated
    label: "Updated"
    visible: true
    order: 7
    width: small
    format: MMM D

  - id: notes
    label: "Notes"
    visible: false            # suppressed for exec view
```

```yaml
# presentation/team-standup-table.yaml
artifact_type: tracking_table
audience: team
label_style: casual

fields:
  - id: project_name
    label: "Project"
    visible: true
    order: 1

  - id: owner
    label: "Owner"            # less formal than "DRI"
    visible: true
    order: 2

  - id: status
    label: "Health"           # different label, same field
    visible: true
    order: 3
    render_as: pill

  - id: milestone
    label: "Milestone"
    visible: true
    order: 4

  - id: target_date
    label: "Due"
    visible: true
    order: 5
    format: MM/DD

  - id: blockers
    label: "Blockers / Risks"
    visible: true
    order: 6

  - id: notes
    label: "Notes"
    visible: true             # visible for team, hidden for exec
    order: 7

  - id: last_updated
    label: "Last Updated"
    visible: false            # team already knows; suppress
```

**Rules:**
- Every visible field must have a `label`. Labels can be anything — they're purely cosmetic.
- Fields absent from a presentation config default to `visible: false`.
- `order` controls column/card sequence. Gaps are fine (e.g., 1, 2, 5) — they're sorted, not indexed.

---

## Layer 3 — Mapping (external data intake)

Translates another PM's terminology into your schema `id`s. One mapping file per external source.

```yaml
# mappings/team-alpha-weekly-report.yaml
source: "Team Alpha Weekly Status Report"
source_format: xlsx           # xlsx | csv | paste | pptx

field_map:
  - source_column: "Work Stream"
    schema_id: project_name

  - source_column: "Responsible Party"
    schema_id: owner

  - source_column: "Overall Status"
    schema_id: status
    value_map:                # translate their values to your enum
      "On Track": green
      "At Risk": yellow
      "Off Track": red
      "Paused": on_hold
      "Done": complete

  - source_column: "Key Deliverable"
    schema_id: milestone

  - source_column: "Completion Date"
    schema_id: target_date

  - source_column: "Issues"
    schema_id: blockers

  # Fields they have that you don't want:
  # "Budget Code" → omitted (no schema_id = dropped)
  # "Department"  → omitted

  # Fields you want that they don't have:
  # notes, last_updated → will be null/empty after import
```

**Rules:**
- Only mapped fields are imported. Unmapped source columns are silently dropped.
- Schema fields with no mapping entry arrive as `null` — artifacts handle nulls gracefully (show "—" or hide the cell).
- `value_map` is only needed for `enum` fields where their vocabulary differs from yours.

---

## Artifact Manifest Block

Every generated artifact carries this comment block at the top. It ties all three layers together for session continuity and future edits.

```yaml
# ARTIFACT MANIFEST
# schema_version: 1.0
# artifact_type: status_dashboard
# presentation: exec-status-dashboard
# mapping: team-alpha-weekly-report   # null if data was pasted directly
# generated: 2026-06-03
# fields_used: [project_name, owner, status, milestone, target_date, blockers, last_updated]
# fields_suppressed: [notes]
```

---

## How the Layers Interact at Runtime

```
External data (xlsx / paste)
        │
        ▼
  [Mapping Layer]
  source_column → schema_id
  value normalization
        │
        ▼
  [Schema Layer]
  Canonical field store
  Validation, type checking
        │
        ▼
  [Presentation Layer]
  label, order, visibility, render_as
        │
        ▼
  Rendered artifact (React / HTML / SVG)
```

Changing a column label = edit one `label:` line in the presentation config.  
Ingesting a new team's data = write one new mapping file.  
Adding a new tracked field = add to schema, then add to whichever presentation configs should show it.

---

## File Naming Convention

```
schema.yaml                              ← one file, always
presentations/
  exec-status-dashboard.yaml
  team-standup-table.yaml
  summary-card.yaml
  structural-diagram.yaml
mappings/
  team-alpha-weekly-report.yaml
  partner-pm-q2-tracker.yaml
```

---

## Git Repo as Professional IP

The full three-layer model lives in a private git repo. This is the practitioner's tooling — the methodology, schema, presentation configs, and mapping templates accumulated across engagements. It is not deliverable to any client or employer.

**Workflow per engagement:**

```
git pull (latest master model)
        │
        ▼
  Adapt to local context
  - company color scheme
  - visual patterns
  - nomenclature / labels
  (stored as a company-specific presentation config in the repo)
        │
        ▼
  compile
        │
        ▼
  Compiled export (client deliverable)
```

The adapt step produces a company presentation config that lives in the repo. The compile step consumes it to produce the export. The repo retains the full history of adaptations across engagements.

---

## The Compile Step

Compile flattens the three layers into a client-deliverable export. The export contains:

1. **Flattened artifacts** — dashboards, tables, cards rendered to the client's color scheme, visual pattern, and nomenclature. No references to schema IDs, presentation configs, or mapping files.

2. **Constrained agent files** — agent configuration that allows updates to exactly three surfaces:
   - Color scheme
   - Visual pattern (layout, render style)
   - Nomenclature (field labels)

   The agent cannot modify field structure, data types, update mechanics, or artifact logic. It enforces this by operating only against an exposed skin layer — it has no visibility into the underlying model.

```yaml
# compiled/acme-corp/agent-config.yaml
locked: true
org: "Acme Corp"
compiled: 2026-06-03
schema_version: 1.0          # for compatibility checks only; schema not included

updateable_surfaces:
  - color_scheme              # primary, secondary, status colors
  - visual_pattern            # layout density, badge style, font scale
  - nomenclature              # field labels only; field IDs are not exposed

prohibited:
  - field_structure
  - data_types
  - update_mechanics
  - artifact_logic
```

**What is NOT in the compiled export:**
- `schema.yaml`
- Any `presentations/` config files
- Any `mappings/` config files
- Any reference to the three-layer architecture
- Anything that would allow reconstruction of the master model

---

## IP Boundary

| Asset | Owner | Rationale |
|---|---|---|
| Git repo (schema, presentations, mappings) | Practitioner | Methodology and tooling; accumulated professional IP |
| Compiled artifacts | Client / Employer | Output of the engagement; what was delivered |
| Constrained agent files | Client / Employer | Operational capability to maintain what was delivered |
| Adaptation configs (per company) | Practitioner | Stored in repo; records how the model was applied |

This follows the standard consulting work-for-hire model: the client owns the outputs, not the engine that produced them. The compiled export is deliberately opaque to the underlying model — it exposes only the governed update surface, not the architecture beneath it.
