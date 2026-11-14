---
applyTo: '**'
description: Follow Omni agent policy and use code-review-graph for code relationships.
---

Follow [AGENTS.md](../AGENTS.md), including its verification approval gate.

Use code-review-graph before unfamiliar implementation exploration, before shared
contract changes, and after edits. Use search/query for entities and relationships,
impact/flows for blast radius, and change detection for edited files.

Confirm conclusions in source and canonical docs. Check graph freshness; do not
assume hooks update it. Missing graph edges do not prove no impact, and test edges
do not establish executed coverage. Use file/search tools for text and graph gaps.
