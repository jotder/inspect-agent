# graphify
- **graphify** (`.claude/skills/graphify/SKILL.md`) - any input to knowledge graph. Trigger: `/graphify`
When the user types `/graphify`, invoke the Skill tool with `skill: "graphify"` before doing anything else.

# Project memory (repo-local, shared)
All project memory lives in `.claude/memory/` in this repo — NOT in the user-profile
`~/.claude/projects/...` directory. Read `.claude/memory/MEMORY.md` at session start.
When project facts change (progress, decisions, gotchas), update the relevant file under
`.claude/memory/`, keep the MEMORY.md index current, and commit the changes so every
user/machine gets them.
