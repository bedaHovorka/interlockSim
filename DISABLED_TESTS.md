# Disabled Tests

This file tracks tests that have been temporarily disabled and the reasons why.

## No Tests Currently Disabled

All tests are currently enabled and passing.

### Recently Re-enabled Tests

**ContextImmutabilityTest (Re-enabled 2026-01-20)**
- **Issue:** #168 - Issue #153.9: Add Comprehensive Context Refactoring Tests
- **Resolution:** The `freeze()` and `isFrozen()` methods were already exposed in the `EditingContext` interface, so the test file was successfully re-enabled by removing the `.disabled` extension and the `@Disabled` annotation.
- **Tests:** 15 immutability enforcement tests
- **Status:** ✅ RE-ENABLED
