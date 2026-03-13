# Issue Documentation

This directory contains detailed documentation for resolved issues in the Railway Interlocking Simulator project.

## Issue #80: GUI InOut Validation

**Status:** ✅ Implemented (2026-02-06)

**File:** [`issue-80.md`](issue-80.md)

**Summary:**
Implemented GUI validation to prevent saving railway networks with 0 InOut elements. Pre-save check in `MenuBar.performSave()` calls the pure `MenuBar.validateForSave(context)` method and shows a user-friendly error dialog if validation fails.

**Related:**
- Issue #258: Future comprehensive validation framework

---

## Other Issues

- [`issue_291_investigation_report.md`](issue_291_investigation_report.md) — Train passivation fix
- [`issue_311_round_robin_load_balancing.md`](issue_311_round_robin_load_balancing.md) — Round-robin load balancing

---

**Last Updated:** 2026-03-13
