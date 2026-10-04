#!/usr/bin/env python3
"""Re-baseline the JUnit 5 @Timeout values of the interlockSim test suites from measured runtimes.

Purpose
    A @Timeout is a hang detector: it should fire soon after a test stops making progress. This
    script reads the JUnit XML of at least MIN_RUN_SETS complete gate runs, maps every <testcase>
    back to the @Timeout annotation that governs it, and proposes (or applies) a tighter value. It
    was written for Issue #753; docs/KOTLIN_STYLE_GUIDE.md ("Test Timeouts") states the rule for
    humans.

Inputs
    --repo     the checkout whose @Timeout lines are read and, with --apply, rewritten. Kotlin test
               sources come from MODULES below (core jvmTest + commonTest, desktop-ui test,
               dispatcher-agent test).
    --timings  a folder of run sets, one sub-folder per complete gate run, each holding the copied
               JUnit XML: <timings>/<run>/<module>/build/test-results/<task>/TEST-*.xml for the tasks
               in MODULES (copy */build/test-results/{jvmTest,test,integrationTest}/ after each run).
               At least MIN_RUN_SETS sub-folders are needed and the baseline set counts; each folder
               is trusted as one complete run. The minimum bounds the runs behind a p95, not the
               coverage per annotation: a class-level @Timeout pools the methods it governs, so it
               can pass MIN_SAMPLES from fewer runs.
    --baseline-set  the run (sub-folder name) taken on the branch being re-baselined. A testcase
               that is absent from it and matches no method of this branch belongs to another
               branch and is ignored.

Rule
    proposal = round_up_to_GRID(max(FACTOR x p95, FLOOR_S))       (3 x p95, at least 10 s)
    new      = min(current, proposal)                              (never raise a value)
    Method-level @Timeout: p95 over every invocation (each repetition / parameter set).
    Class-level @Timeout: the highest per-method p95 among the methods it governs (own methods,
    @Nested classes without their own value, inherited test methods); a method's sample per run is
    its slowest invocation. JUnit does not apply a class-level @Timeout to lifecycle methods, but the
    testcase time includes @BeforeEach/@AfterEach and the suite's @BeforeAll/@AfterAll overhead is
    added as one more sample: deliberate extra margin.
    Actions: lower (new < current), keep, would-raise (3 x p95 > current; left unchanged),
    no-data (< MIN_SAMPLES samples, or an ambiguous testcase-to-method mapping), excluded (tables
    below). Rewrites change only the number, plus the unit when a MINUTES value is no longer a
    whole number of minutes.

Usage
    python3 scripts/rebaseline_timeouts.py --repo . --timings /path/to/timings \\
        --baseline-set develop-1 --out build/timeout-rebaseline            # dry run: CSV + summary
    python3 scripts/rebaseline_timeouts.py ... --apply                     # rewrite lowered values
    --include-ollama / --include-coupled re-baseline the scopes those exclusion tables keep.
"""

from __future__ import annotations

import argparse
import csv
import math
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

MODULES = {
    "core": {"src": ["core/src/jvmTest/kotlin", "core/src/commonTest/kotlin"], "tasks": ["jvmTest", "integrationTest"]},
    "desktop-ui": {"src": ["desktop-ui/src/test/kotlin"], "tasks": ["test", "integrationTest"]},
    "dispatcher-agent": {"src": ["dispatcher-agent/src/test/kotlin"], "tasks": ["test", "integrationTest"]},
}

# Files whose @Timeout values are never touched: file name -> reason.
# Any other @Tag("heavy-test") class or method is excluded as well (heavy tests never run in the gate).
EXCLUDED_FILES = {
    "ThreeTrainLoopRaceHeavyTest.kt": "heavy-test: never runs in the gate, nothing to measure",
    "ShuntingLoopHeavyTest.kt": "heavy-test: never runs in the gate, nothing to measure",
    "TrainFrontIdentityConcurrentReadHeavyTest.kt": "heavy-test: never runs in the gate, nothing to measure",
    "DispatchTickLoopStabilityHeavyTest.kt": "heavy-test: never runs in the gate, nothing to measure",
    "Issue1025CommittedTrainReleaseHeavyTest.kt": "heavy-test: never runs in the gate, nothing to measure",
    "Issue1025StaleTailReleaseHeavyTest.kt": "heavy-test: never runs in the gate, nothing to measure",
    "RuleBasedDispatcherDeterminismHeavyTest.kt": "heavy-test: never runs in the gate, nothing to measure",
    "ShuntingLoopRegressionTest.kt": "owner decision D17: the 120 s timeout is the regression assertion itself",
}

# @Timeout values that the test's KDoc or body names as its own budget (same reasoning as D17):
# (file name, method) -> reason. Re-baselined only with --include-coupled.
BUDGET_COUPLED = {
    ("SimulationSpeedPerformanceTest.kt", "shuntingLoop300sAt100xCompletesQuickly"):
        "KDoc: completion within the @Timeout budget is the primary assertion; in-body await(55 s)",
    ("SimulationSpeedPerformanceTest.kt", "edtRemainsResponsiveDuringSimulation"):
        "in-body comment pins simBlockTimeoutS = 20 s to fit within the @Timeout(30s) budget",
}

# Join guards: scopes whose body hands an in-body wait to a termination-asserting helper
# (testutil/ConcurrentReadProbe: "simulation thread terminated" / "reader thread N terminated").
# The @Timeout must stay above that wait or it fires before the helper's own diagnostic.
# (file name, method) -> in-body wait in seconds. The value becomes
# min(current, max(proposal, next grid step strictly above the wait)).
# This covers one hung thread; the probe joins sequentially (sim, then each reader), so two hung
# readers need more than this value. An explicit list rather than a body scan: a generic
# join/await rule would also move waits that carry no diagnostic of their own.
JOIN_GUARD = {
    # JOIN_TIMEOUT_MILLIS = 4 min
    ("MultiTrainLoopSnapshotRaceTest.kt", "approved train readers are safe off the simulation thread"): 240,
    # JOIN_TIMEOUT_MILLIS = 8 min
    ("ShuntingLoopApprovedTrainsRaceTest.kt", "approved train reads are safe off the simulation thread"): 480,
}

# Classes whose methods carry this tag keep their values unless --include-ollama: their runtime is
# the live local Ollama server (warm or cold model, shared GPU), not the test.
OLLAMA_TAG = "ollama-test"

GRID = [10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1200, 1800, 3600]  # seconds
FLOOR_S = 10  # lowest proposal
FACTOR = 3  # proposal = FACTOR x p95
MIN_SAMPLES = 4  # fewer samples: no-data, value unchanged
MIN_RUN_SETS = 4  # fewer complete run sets: refuse to run (the baseline set counts)

TIMEOUT_RE = re.compile(
    r"@Timeout\(\s*(?:value\s*=\s*)?(?P<num>\d+)\s*,\s*unit\s*=\s*(?P<unit>(?:TimeUnit\.)?(?:SECONDS|MINUTES))"
    r"(?P<rest>[^)]*)\)"
)
DECL_CLASS_RE = re.compile(
    r"^(?P<mods>(?:(?:public|private|internal|protected|abstract|open|inner|data|sealed|enum|annotation|final|companion)\s+)*)"
    r"(?P<kind>class|object|interface)\s+(?P<name>`[^`]+`|\w+)(?P<rest>.*)$"
)
DECL_FUN_RE = re.compile(
    r"^(?:(?:public|private|internal|protected|abstract|open|override|final|suspend|inline)\s+)*"
    r"fun\s+(?:<[^>]*>\s*)?(?P<name>`[^`]+`|\w+)\s*\("
)

# Kotlin string templates: "$name" or "${name}" (a "$" before a digit is not a template).
TEMPLATE_RE = re.compile(r"\$(?:\{[^}]*\}|[A-Za-z_]\w*)")
# The same grammar applied to re.escape()d text ("\$", "\{", ...).
TEMPLATE_ESCAPED_RE = re.compile(r"\\\$(?:\\\{[^}]*\\\}|[A-Za-z_]\w*)")


# --------------------------------------------------------------------------- Kotlin source model


@dataclass
class Annotation:
    file: Path
    line_no: int  # 1-based
    seconds: int
    unit: str  # "SECONDS" | "MINUTES" (the TimeUnit. prefix is stripped once, at parse time)
    module: str
    owner: str  # JVM class name
    method: str | None = None

    @property
    def scope(self) -> str:
        return "method" if self.method is not None else "class"


@dataclass
class Method:
    name: str
    annotations: list[str]
    timeout: Annotation | None = None
    kind: str = "plain"  # test | param | repeated | factory | template | lifecycle | plain
    display: str | None = None  # literal display name, or regex when templated
    display_is_regex: bool = False
    name_pattern: str | None = None  # regex for invocation names of param/repeated tests


@dataclass
class KClass:
    module: str
    file: Path
    package: str
    chain: list[str]  # simple names, outermost first
    supertypes: list[str]
    annotations: list[str]
    parent: "KClass | None"
    methods: dict[str, Method] = field(default_factory=dict)
    timeout: Annotation | None = None
    nested: bool = False

    @property
    def jvm_name(self) -> str:
        return f"{self.package}.{'$'.join(self.chain)}" if self.package else "$".join(self.chain)

    @property
    def simple(self) -> str:
        return self.chain[-1]


def strip_ticks(name: str) -> str:
    return name[1:-1] if name.startswith("`") and name.endswith("`") else name


STRING_LIT_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def kotlin_string_concat(text: str) -> tuple[str, bool]:
    """Concatenate every string literal in an annotation argument; second value is True if templated."""
    parts = []
    templated = False
    for m in STRING_LIT_RE.finditer(text):
        raw = m.group(1)
        out = []
        i = 0
        while i < len(raw):
            c = raw[i]
            if c == "\\" and i + 1 < len(raw):
                n = raw[i + 1]
                if n == "u" and i + 5 < len(raw):
                    out.append(chr(int(raw[i + 2 : i + 6], 16)))
                    i += 6
                    continue
                out.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b"}.get(n, n))
                i += 2
                continue
            if c == "$" and TEMPLATE_RE.match(raw, i):
                templated = True
            out.append(c)
            i += 1
        parts.append("".join(out))
    return "".join(parts), templated


def pattern_to_regex(pattern: str, display: str) -> str:
    """JUnit invocation-name pattern ({index}, {0}, {arguments}, ...) to a full-match regex."""
    out = []
    for tok in re.split(r"(\{[^}]*\})", pattern):
        if not tok:
            continue
        if tok == "{displayName}":
            out.append(re.escape(display))
        elif tok in ("{index}", "{currentRepetition}", "{totalRepetitions}"):
            out.append(r"\d+")
        elif tok.startswith("{"):
            out.append(r".*")
        else:
            out.append(re.escape(tok))
    return "^" + "".join(out) + "$"


def leading_tabs(line: str) -> int:
    return len(line) - len(line.lstrip("\t"))


def ann_named(anns: list[str], name: str) -> list[str]:
    return [a for a in anns if re.match(rf"@{name}\b", a)]


def collect_annotation(lines: list[str], i: int, ln: int, stripped: str, pending: list[tuple[int, str]]) -> int:
    """Consume an annotation (joined with its continuation lines); returns the advanced line index."""
    text = stripped
    depth = paren_delta(text)
    while depth > 0 and i < len(lines):
        nxt = lines[i].strip()
        i += 1
        text += " " + nxt
        depth += paren_delta(nxt)
    # several annotations on one line: split at top-level '@'
    for part in split_annotations(text):
        pending.append((ln, part))
    return i


def build_class(module, path, package, parent, stripped, i, pending, lines) -> KClass:
    """Parse one class declaration (its header may continue over following lines) with its pending annotations."""
    cm = DECL_CLASS_RE.match(stripped)
    header = stripped
    j = i
    while "{" not in header and j < len(lines) and j < i + 15:
        nxt = lines[j].strip()
        if DECL_CLASS_RE.match(nxt) or DECL_FUN_RE.match(nxt) or nxt.startswith("@"):
            break
        header += " " + nxt
        j += 1
    name = strip_ticks(cm.group("name"))
    anns = [a for _, a in pending]
    sup = []
    hm = re.search(r"\)\s*:\s*([^{]*)|^[^(]*?:\s*([^{]*)", header[header.find(name) + len(name) :])
    if hm:
        st = (hm.group(1) or hm.group(2) or "").strip()
        sup = [re.split(r"[(<\s]", s.strip())[0] for s in st.split(",") if s.strip()]
    kc = KClass(
        module=module,
        file=path,
        package=package,
        chain=(parent.chain if parent else []) + [name],
        supertypes=sup,
        annotations=anns,
        parent=parent,
        nested=bool(ann_named(anns, "Nested")),
    )
    kc.timeout = pending_timeout(pending, path, module, kc.jvm_name, None)
    return kc


def build_method(module, path, owner: KClass, fm, pending) -> Method:
    """Parse one method declaration with its pending annotations."""
    name = strip_ticks(fm.group("name"))
    anns = [a for _, a in pending]
    m = Method(name=name, annotations=anns)
    classify_method(m)
    m.timeout = pending_timeout(pending, path, module, owner.jvm_name, name)
    return m


def parse_file(module: str, path: Path) -> list[KClass]:
    lines = path.read_text(encoding="utf-8").split("\n")
    package = ""
    classes: list[KClass] = []
    stack: list[tuple[int, KClass]] = []
    pending: list[tuple[int, str]] = []  # (line_no, annotation text)
    in_block_comment = False
    in_raw = False
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        ln = i + 1
        i += 1
        if in_raw:
            if line.count('"""') % 2 == 1:
                in_raw = False
            continue
        if in_block_comment:
            if "*/" in stripped:
                in_block_comment = False
            continue
        if not stripped or stripped.startswith("//"):
            continue
        if stripped.startswith("/*"):
            if "*/" not in stripped:
                in_block_comment = True
            continue
        if stripped.startswith("package "):
            package = stripped.split()[1]
            continue
        indent = leading_tabs(line)
        # pop finished classes
        while stack and (
            indent < stack[-1][0] or (indent == stack[-1][0] and not stripped.startswith((")", ":", "{", ",")))
        ):
            stack.pop()
        if stripped.startswith("@"):
            i = collect_annotation(lines, i, ln, stripped, pending)
            continue
        cm = DECL_CLASS_RE.match(stripped)
        fm = DECL_FUN_RE.match(stripped)
        if cm:
            kc = build_class(module, path, package, stack[-1][1] if stack else None, stripped, i, pending, lines)
            classes.append(kc)
            stack.append((indent, kc))
            pending = []
            continue
        if fm and stack and indent == stack[-1][0] + 1:
            owner = stack[-1][1]
            m = build_method(module, path, owner, fm, pending)
            owner.methods.setdefault(m.name, m)
            pending = []
            if line.count('"""') % 2 == 1:
                in_raw = True
            continue
        if line.count('"""') % 2 == 1:
            in_raw = True
        pending = []
    return classes


def paren_delta(text: str) -> int:
    """Net '(' minus ')' outside string literals."""
    return sum(1 if c == "(" else -1 for c in STRING_LIT_RE.sub('""', text) if c in "()")


def split_annotations(text: str) -> list[str]:
    # mask string literals so a quote or paren inside one cannot flip the scan state
    mask = STRING_LIT_RE.sub('""', text)
    parts: list[str] = []
    depth = 0
    start = 0
    for k, c in enumerate(mask):
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "@" and depth == 0 and k > start:
            parts.append(text[start:k].strip())
            start = k
    parts.append(text[start:].strip())
    return [p for p in parts if p]


def make_annotation(path, ln, text, module, owner, method) -> Annotation:
    m = TIMEOUT_RE.search(text)
    if not m:
        raise SystemExit(f"Unparsed @Timeout form at {path}:{ln}: {text}")
    num = int(m.group("num"))
    unit = m.group("unit").removeprefix("TimeUnit.")
    seconds = num * 60 if unit == "MINUTES" else num
    return Annotation(file=path, line_no=ln, seconds=seconds, unit=unit, module=module, owner=owner, method=method)


def pending_timeout(pending, path, module, owner_jvm, method) -> Annotation | None:
    """The @Timeout of a pending annotation block, or None; fail-loud on duplicates and unparsed forms."""
    hits = [(ln, a) for ln, a in pending if ann_named([a], "Timeout")]
    if len(hits) > 1:
        raise SystemExit(f"Duplicate @Timeout at {path}: lines {hits[0][0]} and {hits[1][0]}")
    if not hits:
        return None
    return make_annotation(path, hits[0][0], hits[0][1], module, owner_jvm, method)


def pattern_arg(ann: str, default: str) -> str:
    """The name = argument of a @ParameterizedTest/@RepeatedTest annotation (default when absent or empty)."""
    pm = re.search(r"name\s*=", ann)
    if not pm:
        return default
    raw, _ = kotlin_string_concat(ann[pm.end() :])
    return raw or default


def classify_method(m: Method) -> None:
    anns = m.annotations
    dn = ann_named(anns, "DisplayName")
    if dn:
        m.display, templ = kotlin_string_concat(dn[0])
        m.display_is_regex = templ
    display = m.display if m.display is not None else f"{m.name}()"
    pt = ann_named(anns, "ParameterizedTest")
    rt = ann_named(anns, "RepeatedTest")
    if pt:
        m.kind = "param"
        m.name_pattern = pattern_to_regex(pattern_arg(pt[0], "[{index}] {argumentsWithNames}"), display)
    elif rt:
        m.kind = "repeated"
        pattern = pattern_arg(rt[0], "repetition {currentRepetition} of {totalRepetitions}")
        if "RepeatedTest.LONG_DISPLAY_NAME" in rt[0]:
            pattern = "{displayName} :: repetition {currentRepetition} of {totalRepetitions}"
        m.name_pattern = pattern_to_regex(pattern, display)
    elif ann_named(anns, "TestFactory"):
        m.kind = "factory"
    elif ann_named(anns, "TestTemplate"):
        m.kind = "template"
    elif ann_named(anns, "Test"):
        m.kind = "test"
    elif any(ann_named(anns, n) for n in ("BeforeEach", "AfterEach", "BeforeAll", "AfterAll")):
        m.kind = "lifecycle"


# --------------------------------------------------------------------------- index + resolution


class Index:
    def __init__(self, repo: Path):
        self.classes: dict[str, dict[str, KClass]] = {}  # module -> jvm name -> class
        self.annotations: list[Annotation] = []
        for module, cfg in MODULES.items():
            self.classes[module] = {}
            for src in cfg["src"]:
                for f in sorted((repo / src).rglob("*.kt")):
                    for kc in parse_file(module, f):
                        self.classes[module][kc.jvm_name] = kc
        for module, cmap in self.classes.items():
            for kc in cmap.values():
                if kc.timeout:
                    self.annotations.append(kc.timeout)
                for m in kc.methods.values():
                    if m.timeout:
                        self.annotations.append(m.timeout)
        self.annotations.sort(key=lambda a: (a.file, a.line_no))
        self.by_simple: dict[str, dict[str, list[KClass]]] = {}
        for module, cmap in self.classes.items():
            d = defaultdict(list)
            for kc in cmap.values():
                d[kc.simple].append(kc)
            self.by_simple[module] = d
        self.file_classes: dict[Path, list[KClass]] = defaultdict(list)
        for cmap in self.classes.values():
            for kc in cmap.values():
                self.file_classes[kc.file].append(kc)
        # per-row all_methods()/class_level_governor() calls are >99% repeats: memoize both
        self._methods_cache: dict[tuple[str, str], dict[str, Method]] = {}
        self._governor_cache: dict[tuple[str, str], Annotation | None] = {}

    def superclass(self, kc: KClass) -> KClass | None:
        for st in kc.supertypes:
            cands = self.by_simple[kc.module].get(st, [])
            same_pkg = [c for c in cands if c.package == kc.package]
            pick = same_pkg if same_pkg else cands
            if len(pick) == 1:
                return pick[0]
        return None

    def superclass_chain(self, kc: KClass):
        """kc and every superclass of it, outermost first (cycle-safe)."""
        cur: KClass | None = kc
        seen: set[tuple[str, str]] = set()
        while cur is not None and (cur.module, cur.jvm_name) not in seen:
            seen.add((cur.module, cur.jvm_name))
            yield cur
            cur = self.superclass(cur)

    def resolve_classname(self, module: str, classname: str) -> KClass | None:
        cmap = self.classes[module]
        if classname in cmap:
            return cmap[classname]
        parts = classname.split(".")
        for k in range(len(parts) - 1, 0, -1):
            cand = ".".join(parts[:k]) + "$" + "$".join(parts[k:])
            if cand in cmap:
                return cmap[cand]
        return None

    def all_methods(self, kc: KClass) -> dict[str, Method]:
        """Methods of a class including inherited ones (own definitions win)."""
        key = (kc.module, kc.jvm_name)
        if key not in self._methods_cache:
            out: dict[str, Method] = {}
            for c in self.superclass_chain(kc):
                for n, m in c.methods.items():
                    out.setdefault(n, m)
            self._methods_cache[key] = out
        return self._methods_cache[key]

    def class_level_governor(self, kc: KClass) -> Annotation | None:
        """Class-level @Timeout that JUnit applies to a test running in kc (@Inherited + enclosing @Nested)."""
        key = (kc.module, kc.jvm_name)
        if key not in self._governor_cache:
            gov: Annotation | None = None
            cur: KClass | None = kc
            while cur is not None and gov is None:
                for c in self.superclass_chain(cur):
                    if c.timeout:
                        gov = c.timeout
                        break
                cur = cur.parent if cur.nested else None
            self._governor_cache[key] = gov
        return self._governor_cache[key]

    def is_heavy(self, kc: KClass, m: Method | None) -> bool:
        return self.has_tag(kc, m, "heavy-test")

    def governs_tag(self, kc: KClass, tag: str) -> bool:
        """True if a method of kc or of a class nested in it carries the tag."""
        needle = f'"{tag}"'
        for c in self.file_classes.get(kc.file, []):
            if c.chain[: len(kc.chain)] == kc.chain:
                for mm in c.methods.values():
                    if any(needle in x for x in ann_named(mm.annotations, "Tag")):
                        return True
        return False

    def has_tag(self, kc: KClass, m: Method | None, tag: str) -> bool:
        needle = f'"{tag}"'
        if m is not None and any(needle in a for a in ann_named(m.annotations, "Tag")):
            return True
        cur: KClass | None = kc
        while cur is not None:
            if any(needle in a for a in ann_named(cur.annotations, "Tag")):
                return True
            cur = cur.parent
        return False


def ann_key(a: Annotation) -> str:
    return f"{a.file}:{a.line_no}"


def match_candidates(methods: dict[str, Method], name: str) -> list[Method]:
    mm = re.match(r"^(.*)\([^()]*\)$", name)
    if mm and mm.group(1) in methods and methods[mm.group(1)].display is None:
        return [methods[mm.group(1)]]
    out = []
    literal = []
    for m in methods.values():
        if m.kind in ("lifecycle", "plain"):
            continue
        if m.kind in ("param", "repeated"):
            if m.name_pattern and re.match(m.name_pattern, name, re.S):
                out.append(m)
            continue
        if m.kind in ("factory", "template"):
            out.append(m)
            continue
        if m.display is not None:
            if m.display_is_regex:
                rx = "^" + TEMPLATE_ESCAPED_RE.sub(".*", re.escape(m.display)) + "$"
                if re.match(rx, name, re.S):
                    out.append(m)
            elif m.display == name:
                out.append(m)
                literal.append(m)
    # A fixed @DisplayName that equals the testcase name exactly wins over invocation-name patterns
    # ({0}, {arguments}, ...) and dynamic tests, which only match by wildcard.
    return literal if len(literal) == 1 else out


# --------------------------------------------------------------------------- samples


def p95(values: list[float]) -> float:
    s = sorted(values)
    k = max(0, math.ceil(0.95 * len(s)) - 1)  # nearest-rank
    return s[k]


def round_up_grid(seconds: float) -> int:
    for g in GRID:
        if seconds <= g:
            return g
    return int(math.ceil(seconds / 600.0) * 600)


def canonical_name(name: str) -> str:
    """Testcase name without the '[jvm]' target label KMP glues on when a class runs on several
    targets (the linuxX64Test XML situation of the gradle#33990 workaround, core/CLAUDE.md)."""
    return name[: -len("[jvm]")] if name.endswith("[jvm]") else name


@dataclass
class Samples:
    """Everything collect() gathers from the run sets, keyed by annotation (ann_key)."""

    inv_samples: dict[str, list[float]] = field(default_factory=lambda: defaultdict(list))
    cls_samples: dict[str, dict[tuple, float]] = field(default_factory=lambda: defaultdict(dict))
    tainted: dict[str, str] = field(default_factory=dict)
    unresolved: Counter = field(default_factory=Counter)
    stats: Counter = field(default_factory=Counter)

    def add_class_sample(self, ann: Annotation, mkey: tuple, t: float) -> None:
        """Record one sample under a class-level annotation: per run and method, the slowest."""
        d = self.cls_samples[ann_key(ann)]
        d[mkey] = max(d.get(mkey, 0.0), t)

    def add_testcase(
        self, index: Index, module: str, set_name: str, tc: ET.Element, t: float, baseline: set
    ) -> KClass | None:
        """Record one testcase under every annotation of this branch that governs it.

        Returns the class the testcase ran in, or None when its classname resolves to no class
        of the checkout. A class-level sample lands on the class annotation only when that value
        really governs the testcase: a method with its own @Timeout runs under that value, and a
        testcase that exists only on another branch runs under no annotation of this branch, so
        neither may tighten the class value.
        """
        name = canonical_name(tc.get("name"))
        cn = tc.get("classname")
        self.stats["cases"] += 1
        kc = index.resolve_classname(module, cn)
        if kc is None:
            self.unresolved[(module, cn)] += 1
            return None
        methods = index.all_methods(kc)
        cands = match_candidates(methods, name)
        cg = index.class_level_governor(kc)
        if len(cands) == 1:
            m = cands[0]
            self.stats["matched"] += 1
            if m.timeout is not None:
                self.inv_samples[ann_key(m.timeout)].append(t)
            elif cg is not None:
                # no method value: the class annotation governs this testcase
                self.add_class_sample(cg, (set_name, kc.jvm_name, m.name), t)
        elif not cands and (module, cn, name) not in baseline:
            # a test that exists only on another branch: no annotation of this branch governs it
            self.stats["unmatched-branch-only"] += 1
        elif not cands:
            self.stats["unmatched"] += 1
            if cg is not None:
                # the '?' prefix marks a sample whose method identity the testcase name cannot resolve
                self.add_class_sample(cg, (set_name, kc.jvm_name, f"?{name}"), t)
            # it could belong to any non-trivially named method of the class: taint those
            for m in methods.values():
                if m.timeout is not None and (m.display is not None or m.kind != "test"):
                    self.tainted.setdefault(ann_key(m.timeout), f"unmatched testcase '{name}' in {cn}")
        else:
            self.stats["ambiguous"] += 1
            if cg is not None:
                self.add_class_sample(cg, (set_name, kc.jvm_name, f"?{name}"), t)
            for m in cands:
                if m.timeout is not None:
                    self.tainted.setdefault(ann_key(m.timeout), f"testcase '{name}' matches {len(cands)} methods in {cn}")
        return kc


def collect(index: Index, timings: Path, baseline_set: str) -> tuple[list[Path], Samples]:
    sets = sorted(p for p in timings.iterdir() if p.is_dir())
    if baseline_set not in {s.name for s in sets}:
        raise SystemExit(
            f"baseline set '{baseline_set}' is not one of the run sets in {timings}: "
            + (", ".join(s.name for s in sets) or "none found")
        )
    if len(sets) < MIN_RUN_SETS:
        raise SystemExit(
            f"need at least {MIN_RUN_SETS} run sets in {timings} (the baseline set counts), "
            f"found {len(sets)}: " + (", ".join(s.name for s in sets) or "none found")
        )
    data = Samples()
    # testcase names that exist on the branch being re-baselined (the baseline run set)
    baseline: set[tuple[str, str, str]] = set()
    for module, cfg in MODULES.items():
        for task in cfg["tasks"]:
            for xf in (timings / baseline_set / module / "build" / "test-results" / task).glob("TEST-*.xml"):
                for tc in ET.parse(xf).getroot().iter("testcase"):
                    baseline.add((module, tc.get("classname"), canonical_name(tc.get("name"))))
    if not baseline:
        raise SystemExit(
            f"no <testcase> found in the baseline set '{baseline_set}': expected "
            f"{timings / baseline_set}/<module>/build/test-results/<task>/TEST-*.xml"
        )
    for s in sets:
        for module, cfg in MODULES.items():
            for task in cfg["tasks"]:
                d = s / module / "build" / "test-results" / task
                for xf in sorted(d.glob("TEST-*.xml")):
                    root = ET.parse(xf).getroot()
                    suite_time = float(root.get("time") or 0)
                    case_sum = 0.0
                    suite_cls = None
                    for tc in root.iter("testcase"):
                        t = float(tc.get("time") or 0)
                        case_sum += t
                        if tc.find("skipped") is not None:
                            data.stats["skipped"] += 1
                            continue
                        kc = data.add_testcase(index, module, s.name, tc, t, baseline)
                        if kc is not None:
                            suite_cls = kc
                    # @BeforeAll/@AfterAll overhead: JUnit does not apply the class-level @Timeout to lifecycle
                    # methods; the overhead is counted as one more sample on purpose, as extra margin.
                    if suite_cls is not None:
                        cg = index.class_level_governor(suite_cls)
                        overhead = suite_time - case_sum
                        if cg is not None and overhead > 0:
                            data.add_class_sample(cg, (s.name, suite_cls.jvm_name, "<suite-overhead>"), overhead)
    return sets, data


# --------------------------------------------------------------------------- rewrite


def rewrite(text: str, new_seconds: int, unit: str) -> str:
    m = TIMEOUT_RE.search(text)
    assert m
    if unit == "MINUTES":
        if new_seconds % 60 == 0:
            new_seconds //= 60
        else:
            # the number sits left of the unit span, so replace the unit first and keep the number span valid
            text = text[: m.start("unit")] + "TimeUnit.SECONDS" + text[m.end("unit") :]
    return text[: m.start("num")] + str(new_seconds) + text[m.end("num") :]


def fmt_unit(seconds: int, unit: str) -> str:
    if unit == "MINUTES" and seconds % 60 == 0:
        return f"{seconds // 60} min"
    return f"{seconds} s"


# --------------------------------------------------------------------------- row decision


def exclusion_reason(index, a, kc, m, include_coupled, include_ollama) -> str | None:
    """Why the annotation is out of re-baselining scope, or None when the tables keep it."""
    if a.file.name in EXCLUDED_FILES:
        return "excluded file: " + EXCLUDED_FILES[a.file.name]
    if index.is_heavy(kc, m):
        return "heavy-test tag"
    if not include_coupled and (a.file.name, a.method) in BUDGET_COUPLED:
        return "budget-coupled: " + BUDGET_COUPLED[(a.file.name, a.method)]
    if not include_ollama and (
        index.has_tag(kc, m, OLLAMA_TAG)
        or (a.scope == "class" and index.governs_tag(kc, OLLAMA_TAG))
    ):
        return "ollama-test tag: runtime is a live external service, not the test"
    return None


def decide_row(a, index: Index, data: Samples, repo: Path, include_coupled: bool, include_ollama: bool) -> dict:
    """The CSV row for one annotation: the action decided from the samples that map to it."""
    kc = index.classes[a.module][a.owner]
    m = kc.methods.get(a.method) if a.method else None
    key = ann_key(a)
    samples: list[float] = []
    reason = exclusion_reason(index, a, kc, m, include_coupled, include_ollama)
    action = "excluded" if reason is not None else None
    if action is None:
        samples = list(data.cls_samples.get(key, {}).values()) if a.scope == "class" else data.inv_samples.get(key, [])
        if a.scope == "method" and m is not None and m.kind not in ("test", "param", "repeated"):
            action, reason = "no-data", f"method kind {m.kind}"
        elif key in data.tainted:
            action, reason = "no-data", "ambiguous mapping: " + data.tainted[key]
        elif len(samples) < MIN_SAMPLES:
            action, reason = "no-data", f"{len(samples)} samples"
    row = {
        "module": a.module,
        "file": str(a.file.relative_to(repo)),
        "line": a.line_no,
        "scope": a.scope,
        "target": a.owner.split(".")[-1] + (f"#{a.method}" if a.method else ""),
        "kind": (m.kind if m else "class"),
        "current_s": a.seconds,
        "unit": a.unit,
        "samples": len(samples),
        "max_s": f"{max(samples):.3f}" if samples else "",
        "p95_s": "",
        "x3_p95_s": "",
        "proposal_s": "",
        "pooled_p95_s": "",
        "slowest": "",
        "new_s": a.seconds,
        "action": action,
        "reason": reason or "",
    }
    if action is None:
        if a.scope == "class":
            per_method: dict[tuple, list[float]] = defaultdict(list)
            for (_set, jvm, meth), t in data.cls_samples[key].items():
                per_method[(jvm, meth)].append(t)
            worst = max(per_method.items(), key=lambda kv: p95(kv[1]))
            row["slowest"] = f"{worst[0][0].split('.')[-1]}#{worst[0][1]}"
            row["pooled_p95_s"] = f"{p95(samples):.3f}"
            p = p95(worst[1])
        else:
            p = p95(samples)
        proposal = round_up_grid(max(FACTOR * p, FLOOR_S))
        row["p95_s"] = f"{p:.3f}"
        row["x3_p95_s"] = f"{FACTOR * p:.3f}"
        guard = JOIN_GUARD.get((a.file.name, a.method))
        if guard is not None:
            floor = next(g for g in GRID if g > guard)
            if floor > proposal:
                proposal = floor
                row["reason"] = f"join guard: in-body wait {guard} s needs a timeout above it"
        row["proposal_s"] = proposal
        if proposal < a.seconds:
            row["action"], row["new_s"] = "lower", proposal
        elif FACTOR * p > a.seconds:
            row["action"], row["reason"] = "would-raise", "3 x p95 exceeds the current value; left unchanged"
        else:
            row["action"] = "keep"
    row["_ann"] = a
    return row


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Re-baseline JUnit @Timeout values from measured JUnit XML runtimes (Issue #753).",
        epilog="Without --apply this is a dry run: it writes <out>/rebaseline.csv and prints the summary.",
    )
    ap.add_argument("--repo", required=True, type=Path, help="checkout whose @Timeout lines are read/rewritten")
    ap.add_argument(
        "--timings",
        required=True,
        type=Path,
        help="folder of run sets: <timings>/<run>/<module>/build/test-results/<task>/TEST-*.xml",
    )
    ap.add_argument(
        "--baseline-set",
        required=True,
        help="name of the run set taken on the branch being re-baselined (e.g. develop-1)",
    )
    ap.add_argument("--out", type=Path, default=Path("build/timeout-rebaseline"), help="folder for rebaseline.csv")
    ap.add_argument("--apply", action="store_true", help="rewrite the lowered @Timeout values in --repo")
    ap.add_argument("--include-coupled", action="store_true", help="also re-baseline BUDGET_COUPLED annotations")
    ap.add_argument("--include-ollama", action="store_true", help=f'also re-baseline @Tag("{OLLAMA_TAG}") scopes')
    args = ap.parse_args()

    index = Index(args.repo)
    sets, data = collect(index, args.timings, args.baseline_set)
    print(f"run sets: {', '.join(s.name for s in sets)}")
    print(f"annotations parsed: {len(index.annotations)}")
    print(f"testcases: {dict(data.stats)}; unresolved classnames: {len(data.unresolved)}")
    for (mod, cn), n in sorted(data.unresolved.items()):
        print(f"  unresolved {mod} {cn} x{n}")

    rows = [
        decide_row(a, index, data, args.repo, args.include_coupled, args.include_ollama)
        for a in index.annotations
    ]

    args.out.mkdir(parents=True, exist_ok=True)
    fields = [k for k in rows[0] if not k.startswith("_")]
    with open(args.out / "rebaseline.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: r[k] for k in fields})

    # every exclusion-table entry must bind to a parsed annotation, or a rename has silently
    # orphaned it (the JOIN_GUARD convention)
    used = {(r["_ann"].file.name, r["_ann"].method) for r in rows}
    files = {r["_ann"].file.name for r in rows}
    for k in JOIN_GUARD:
        if k not in used:
            raise SystemExit(f"JOIN_GUARD entry matches no annotation: {k}")
    for k in BUDGET_COUPLED:
        if k not in used:
            raise SystemExit(f"BUDGET_COUPLED entry matches no annotation: {k}")
    for f in EXCLUDED_FILES:
        if f not in files:
            raise SystemExit(f"EXCLUDED_FILES entry matches no annotation: {f}")
    print("\naction counts:", dict(Counter(r["action"] for r in rows)))
    for mod in MODULES:
        mr = [r for r in rows if r["module"] == mod]
        print(f"\n{mod}: {len(mr)} annotations; actions {dict(Counter(r['action'] for r in mr))}")
        before = Counter(fmt_unit(r["current_s"], r["unit"]) for r in mr)
        after = Counter(fmt_unit(r["new_s"], r["unit"]) for r in mr)
        key = lambda s: int(s.split()[0]) * (60 if s.endswith("min") else 1)
        print("  before:", ", ".join(f"{k} x{v}" for k, v in sorted(before.items(), key=lambda kv: key(kv[0]))))
        print("  after: ", ", ".join(f"{k} x{v}" for k, v in sorted(after.items(), key=lambda kv: key(kv[0]))))

    if args.apply:
        by_file = defaultdict(list)
        for r in rows:
            if r["action"] == "lower":
                by_file[r["_ann"].file].append(r)
        for f, rs in by_file.items():
            lines = f.read_text(encoding="utf-8").split("\n")
            for r in rs:
                a = r["_ann"]
                idx = a.line_no - 1
                old = lines[idx]
                new = rewrite(old, r["new_s"], a.unit)
                if new == old:
                    raise SystemExit(f"rewrite did nothing at {f}:{a.line_no}")
                lines[idx] = new
            f.write_text("\n".join(lines), encoding="utf-8")
        print(f"\napplied {sum(len(v) for v in by_file.values())} rewrites in {len(by_file)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
