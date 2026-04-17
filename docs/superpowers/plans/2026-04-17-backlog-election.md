# Backlog Election — Parallel Jury Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a parallel jury of 7 TEAM.md subagents to identify the top 3 Backlog issues whose completion most unblocks other open issues.

**Architecture:** Orchestrator fetches all open issues once via `gh` CLI, builds a compact IssueContext, dispatches 7 subagents in parallel (one per TEAM.md role), each scoring every Backlog issue 0–10 on dependency/enabler effect, then applies authority weights to produce the top 3 issue numbers.

**Tech Stack:** `gh` CLI (GitHub API), Claude Code `Agent` tool (7 parallel subagents), Python 3 (inline aggregation)

---

### Task 1: Fetch and validate issue data

**Files:** No files created or modified — pure in-session data cached in `/tmp/`.

- [ ] **Step 1: Fetch all open issues**

```bash
gh issue list --state open --limit 500 \
  --json number,title,labels,body,milestone,assignees,comments \
  > /tmp/interlockSim-issues.json
```

Expected: `/tmp/interlockSim-issues.json` created, no error output.

- [ ] **Step 2: Validate the fetch**

```bash
python3 -c "
import json
with open('/tmp/interlockSim-issues.json') as f:
    issues = json.load(f)
B = [i for i in issues if i.get('milestone') and i['milestone']['title'] == 'Backlog']
print(f'X (all open): {len(issues)}')
print(f'B (Backlog):  {len(B)}')
print('B numbers:', [i['number'] for i in B])
"
```

Expected (approximate):
```
X (all open): 33
B (Backlog):  20
B numbers: [438, 437, 412, 408, 407, 406, 405, 397, 387, 380, 379, 378, 376, 373, 321, 307, 305, 248, 59, 37]
```

If `X == 0` or `B == 0`, stop — the `gh` fetch failed or auth is missing (`gh auth status`).

- [ ] **Step 3: Build compact IssueContext files**

```bash
python3 -c "
import json
with open('/tmp/interlockSim-issues.json') as f:
    issues = json.load(f)
B = [i for i in issues if i.get('milestone') and i['milestone']['title'] == 'Backlog']

def fmt(i):
    body = (i.get('body') or '')[:2000]
    comments = ' | '.join(
        (c.get('body') or '')[:300]
        for c in (i.get('comments') or [])[:5]
    )
    labels = ','.join(l['name'] for l in (i.get('labels') or []))
    return f'''--- ISSUE #{i['number']}: {i['title']} [labels:{labels}]
BODY: {body}
COMMENTS: {comments}
---'''

ctx_x = '\n'.join(fmt(i) for i in issues)
ctx_b = '\n'.join(fmt(i) for i in B)
b_numbers = [str(i['number']) for i in B]

with open('/tmp/ctx-x.txt','w') as f: f.write(ctx_x)
with open('/tmp/ctx-b.txt','w') as f: f.write(ctx_b)
with open('/tmp/b-numbers.json','w') as f: json.dump(b_numbers, f)
print(f'X context: {len(ctx_x)} chars')
print(f'B context: {len(ctx_b)} chars')
print('B numbers:', b_numbers)
"
```

Expected: Three files written, no errors. X context typically 50–150 KB total.

---

### Task 2: Launch 7 parallel jury agents

Send **one message** with all 7 `Agent` tool calls simultaneously. Before dispatching, read the three context files into your session context:

```bash
cat /tmp/ctx-x.txt       # → ISSUE_CONTEXT_X  (substitute into each prompt below)
cat /tmp/ctx-b.txt       # → ISSUE_CONTEXT_B  (substitute into each prompt below)
cat /tmp/b-numbers.json  # → B_NUMBERS        (substitute into each prompt below)
```

Then dispatch all 7 agents in a single parallel message using the prompts below, with `ISSUE_CONTEXT_X`, `ISSUE_CONTEXT_B`, and `B_NUMBERS` replaced by the actual file contents.

- [ ] **Step 1: Verify context files exist before dispatching**

```bash
ls -lh /tmp/ctx-x.txt /tmp/ctx-b.txt /tmp/b-numbers.json
```

Expected: All three present, non-zero size.

- [ ] **Step 2: Dispatch all 7 agents in one parallel message**

Use `subagent_type: general-purpose` for all agents.

---

**Agent 1 prompt — traffic-simulation-expert (weight 3.0)**

```
You are the traffic-simulation-expert on the interlockSim project.
Your domain: sim/ package, kDisco library, train physics, discrete-event
simulation, simulation correctness, framework migration (kDisco→Kalasim).

TASK: Grade every Backlog issue on how much completing it would UNBLOCK
or SIMPLIFY other open issues. Grade from YOUR DOMAIN PERSPECTIVE only.
Issues unrelated to simulation, physics, or kDisco should score low (0–2)
unless they unblock work in your domain.

BACKLOG CANDIDATES — grade each of these:
<INSERT ISSUE_CONTEXT_B HERE>

ALL OPEN ISSUES — use to judge what gets unblocked:
<INSERT ISSUE_CONTEXT_X HERE>

SCORING RUBRIC (0–10 per Backlog issue):
10 = completing this directly unblocks or enables many other open issues
 5 = completing this simplifies or de-risks a few open issues
 0 = no dependency effect on any other open issue

B issue numbers to grade: <INSERT B_NUMBERS HERE>

Return ONLY valid JSON mapping issue number strings to integer scores.
No explanation. No markdown. No code fences.
Example: {"438": 7, "437": 2, "412": 0}
You MUST include every number from B_NUMBERS.
```

---

**Agent 2 prompt — kotlin-tech-lead (weight 2.0)**

```
You are the kotlin-tech-lead on the interlockSim project.
Your domain: software architecture, Kotlin idioms, API design, code quality
tooling (detekt, ktlint, SonarCloud), context system, XML layer, domain model
(objects/), module boundaries between :core and :desktop-ui.

TASK: Grade every Backlog issue on how much completing it would UNBLOCK
or SIMPLIFY other open issues. Grade from YOUR DOMAIN PERSPECTIVE only.
Focus on architecture, code quality tooling, and Kotlin modernization.

BACKLOG CANDIDATES — grade each of these:
<INSERT ISSUE_CONTEXT_B HERE>

ALL OPEN ISSUES — use to judge what gets unblocked:
<INSERT ISSUE_CONTEXT_X HERE>

SCORING RUBRIC (0–10 per Backlog issue):
10 = completing this directly unblocks or enables many other open issues
 5 = completing this simplifies or de-risks a few open issues
 0 = no dependency effect on any other open issue

B issue numbers to grade: <INSERT B_NUMBERS HERE>

Return ONLY valid JSON mapping issue number strings to integer scores.
No explanation. No markdown. No code fences.
Example: {"438": 7, "437": 2, "412": 0}
You MUST include every number from B_NUMBERS.
```

---

**Agent 3 prompt — railway-civil-engineer (weight 2.0)**

```
You are the railway-civil-engineer on the interlockSim project.
Your domain: railway domain correctness, interlocking logic, XML track
configurations (vyhybna.xml, praha.xml), train operations, safety
constraints, railway standards and regulations.

TASK: Grade every Backlog issue on how much completing it would UNBLOCK
or SIMPLIFY other open issues. Grade from YOUR DOMAIN PERSPECTIVE only.
Focus on domain correctness, XML validity, and interlocking safety.

BACKLOG CANDIDATES — grade each of these:
<INSERT ISSUE_CONTEXT_B HERE>

ALL OPEN ISSUES — use to judge what gets unblocked:
<INSERT ISSUE_CONTEXT_X HERE>

SCORING RUBRIC (0–10 per Backlog issue):
10 = completing this directly unblocks or enables many other open issues
 5 = completing this simplifies or de-risks a few open issues
 0 = no dependency effect on any other open issue

B issue numbers to grade: <INSERT B_NUMBERS HERE>

Return ONLY valid JSON mapping issue number strings to integer scores.
No explanation. No markdown. No code fences.
Example: {"438": 7, "437": 2, "412": 0}
You MUST include every number from B_NUMBERS.
```

---

**Agent 4 prompt — java-senior-dev (weight 1.0)**

```
You are the java-senior-dev on the interlockSim project.
Your domain (read-only analysis): legacy Java codebase history, null safety
debt introduced during Kotlin migration, regression risk, historical design
decisions. You analyze, you do not implement.

TASK: Grade every Backlog issue on how much completing it would UNBLOCK
or SIMPLIFY other open issues. Grade from YOUR DOMAIN PERSPECTIVE only.
Focus on technical debt, null safety, and legacy migration concerns.

BACKLOG CANDIDATES — grade each of these:
<INSERT ISSUE_CONTEXT_B HERE>

ALL OPEN ISSUES — use to judge what gets unblocked:
<INSERT ISSUE_CONTEXT_X HERE>

SCORING RUBRIC (0–10 per Backlog issue):
10 = completing this directly unblocks or enables many other open issues
 5 = completing this simplifies or de-risks a few open issues
 0 = no dependency effect on any other open issue

B issue numbers to grade: <INSERT B_NUMBERS HERE>

Return ONLY valid JSON mapping issue number strings to integer scores.
No explanation. No markdown. No code fences.
Example: {"438": 7, "437": 2, "412": 0}
You MUST include every number from B_NUMBERS.
```

---

**Agent 5 prompt — agent-architect (weight 1.0)**

```
You are the agent-architect on the interlockSim project.
Your domain: AI agent system design, multi-agent coordination, tool APIs,
TEAM.md role definitions, build system tooling, developer workflow
infrastructure.

TASK: Grade every Backlog issue on how much completing it would UNBLOCK
or SIMPLIFY other open issues. Grade from YOUR DOMAIN PERSPECTIVE only.
Focus on tooling, build infrastructure, and developer workflow issues.

BACKLOG CANDIDATES — grade each of these:
<INSERT ISSUE_CONTEXT_B HERE>

ALL OPEN ISSUES — use to judge what gets unblocked:
<INSERT ISSUE_CONTEXT_X HERE>

SCORING RUBRIC (0–10 per Backlog issue):
10 = completing this directly unblocks or enables many other open issues
 5 = completing this simplifies or de-risks a few open issues
 0 = no dependency effect on any other open issue

B issue numbers to grade: <INSERT B_NUMBERS HERE>

Return ONLY valid JSON mapping issue number strings to integer scores.
No explanation. No markdown. No code fences.
Example: {"438": 7, "437": 2, "412": 0}
You MUST include every number from B_NUMBERS.
```

---

**Agent 6 prompt — qa-engineer (weight 1.0)**

```
You are a qa-engineer on the interlockSim project.
Your domain: test coverage, quality gates, SonarCloud (≥80% new-code
coverage gate), test infrastructure, GUI testing (Swing, AssertJ-Swing),
regression testing, JUnit 5, AssertK.

TASK: Grade every Backlog issue on how much completing it would UNBLOCK
or SIMPLIFY other open issues. Grade from YOUR DOMAIN PERSPECTIVE only.
Focus on testing infrastructure and issues that block the ability to verify
or test other issues.

BACKLOG CANDIDATES — grade each of these:
<INSERT ISSUE_CONTEXT_B HERE>

ALL OPEN ISSUES — use to judge what gets unblocked:
<INSERT ISSUE_CONTEXT_X HERE>

SCORING RUBRIC (0–10 per Backlog issue):
10 = completing this directly unblocks or enables many other open issues
 5 = completing this simplifies or de-risks a few open issues
 0 = no dependency effect on any other open issue

B issue numbers to grade: <INSERT B_NUMBERS HERE>

Return ONLY valid JSON mapping issue number strings to integer scores.
No explanation. No markdown. No code fences.
Example: {"438": 7, "437": 2, "412": 0}
You MUST include every number from B_NUMBERS.
```

---

**Agent 7 prompt — kotlin-junior-dev (weight 0.5)**

```
You are a kotlin-junior-dev on the interlockSim project.
Your domain: GUI package (gui/), utility classes (util/), good-first-issues,
documentation, code quality fixes (ktlint, detekt). You implement
well-defined tasks under mentorship.

TASK: Grade every Backlog issue on how much completing it would UNBLOCK
or SIMPLIFY other open issues. Grade from YOUR DOMAIN PERSPECTIVE only.
Focus on GUI, documentation, and code-quality issues.

BACKLOG CANDIDATES — grade each of these:
<INSERT ISSUE_CONTEXT_B HERE>

ALL OPEN ISSUES — use to judge what gets unblocked:
<INSERT ISSUE_CONTEXT_X HERE>

SCORING RUBRIC (0–10 per Backlog issue):
10 = completing this directly unblocks or enables many other open issues
 5 = completing this simplifies or de-risks a few open issues
 0 = no dependency effect on any other open issue

B issue numbers to grade: <INSERT B_NUMBERS HERE>

Return ONLY valid JSON mapping issue number strings to integer scores.
No explanation. No markdown. No code fences.
Example: {"438": 7, "437": 2, "412": 0}
You MUST include every number from B_NUMBERS.
```

---

- [ ] **Step 3: Validate each agent response**

For each of the 7 responses, check:
- Parses as valid JSON? If not → assign score 0 for all missing entries.
- Contains all B issue numbers? If any missing → treat missing as 0.
- All values are numeric (0–10)? If out of range → clamp to [0, 10].

---

### Task 3: Aggregate scores and output top 3

- [ ] **Step 1: Compute weighted totals**

Replace each `{ ... }` below with the actual parsed JSON from the corresponding agent response, then run:

```python
import json

# Paste actual agent responses here (issue number keys must be strings)
agent_scores = {
    "traffic-simulation-expert": (3.0, { }),   # paste agent 1 JSON
    "kotlin-tech-lead":          (2.0, { }),   # paste agent 2 JSON
    "railway-civil-engineer":    (2.0, { }),   # paste agent 3 JSON
    "java-senior-dev":           (1.0, { }),   # paste agent 4 JSON
    "agent-architect":           (1.0, { }),   # paste agent 5 JSON
    "qa-engineer":               (1.0, { }),   # paste agent 6 JSON
    "kotlin-junior-dev":         (0.5, { }),   # paste agent 7 JSON
}

totals = {}
for role, (weight, scores) in agent_scores.items():
    for issue, score in scores.items():
        s = max(0, min(10, float(score)))   # clamp
        totals[issue] = totals.get(issue, 0) + weight * s

top3 = sorted(totals, key=lambda k: totals[k], reverse=True)[:3]
print(', '.join(f'#{n}' for n in top3))
```

Note: The `{ }` dicts are filled in at runtime from agent responses — they are not placeholders in the spec sense; they are runtime inputs that exist only after agents return.

- [ ] **Step 2: Print final answer**

Output only the three issue numbers, nothing else:

```
#NNN, #NNN, #NNN
```
