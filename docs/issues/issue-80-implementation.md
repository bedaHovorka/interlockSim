# GUI InOut Validation - Implementation Summary

## Issue #80: Add GUI validation to prevent saving contexts with insufficient InOut elements

### Problem
Users could save railway networks with fewer than 2 InOut elements through the editor GUI, but could not reload them due to XML validation added in PR #76. This created a poor user experience with unclear error messages appearing only on reload.

### Solution Implemented

#### 1. Pre-Save Validation (MenuBar.kt)
Added validation in `MenuBar.performSave()` method that executes **before** attempting to save:

```kotlin
// Validate InOut count before saving (Issue #80)
val inOutCount = editingContext.getInOuts().size
if (inOutCount < MIN_INOUT_ELEMENTS) {
    JOptionPane.showMessageDialog(
        this,
        "Railway network must have at least $MIN_INOUT_ELEMENTS InOut elements (entry and exit points).\n\n" +
            "Current count: $inOutCount\n\n" +
            "InOut elements define where trains enter and exit the railway network.\n" +
            "Please add more InOut elements before saving.",
        "Cannot Save - Insufficient InOut Elements",
        JOptionPane.ERROR_MESSAGE
    )
    return false
}
```

**Key Features:**
- ✅ Checks InOut count before save operation
- ✅ Shows user-friendly error dialog with:
  - Clear requirement (minimum 2 InOut elements)
  - Current count
  - Explanation of what InOut elements are
  - Actionable guidance (add more InOut elements)
- ✅ Prevents save operation by returning `false`
- ✅ Applies to all save paths (Save, Save As, Window Close with Save)

#### 2. Constant Definition
Added `MIN_INOUT_ELEMENTS = 2` constant in MenuBar companion object:
- Matches XMLContextFactory validation threshold
- Single source of truth for minimum requirement
- Easy to update if requirements change

#### 3. Comprehensive Test Coverage (InOutSaveValidationTest.kt)
Created new test class with 5 test cases:

1. **contextWith0InOutsCannotBeSaved** - Verifies save fails with 0 InOuts
2. **contextWith1InOutCannotBeSaved** - Verifies save fails with 1 InOut
3. **contextWith2InOutsCanBeSaved** - Verifies save succeeds with 2 InOuts
4. **contextWith3InOutsCanBeSaved** - Verifies save succeeds with 3+ InOuts
5. **validationMessageIncludesCurrentInOutCount** - Verifies InOut count is accurate

**Test Architecture:**
- Extends `AbstractFrameTestBase` for proper GUI test setup
- Uses `@TempDir` for isolated test files
- Uses reflection to test private `performSave()` method
- Tagged as `@Tag("integration-test")` for GUI environment
- Includes 10-second timeout for each test

### User Experience Improvements

#### Before (Issue)
1. User creates network with 1 InOut
2. User saves successfully ✅
3. User tries to reload ❌
4. Error: "Railway network must have at least 2 InOut elements... Found: 1"
5. User confused - file was saved but can't be loaded

#### After (Fixed)
1. User creates network with 1 InOut
2. User attempts to save ❌
3. **Error dialog appears immediately:**
   - Clear title: "Cannot Save - Insufficient InOut Elements"
   - Current count displayed
   - Explanation provided
   - Actionable guidance
4. User adds another InOut
5. User saves successfully ✅
6. User can reload without issues ✅

### Design Decisions

#### Why validate on save instead of continuously?
- **Editing flexibility**: Users need to work on incomplete networks
- **Non-intrusive**: Only validates when user explicitly saves
- **Consistent with Issue #258**: Editor allows editing any file, validates on save

#### Why not disable save button?
- **Less discoverable**: Users might not understand why button is disabled
- **Error dialog provides context**: Explains the problem and solution
- **Flexible for future validations**: Can add warnings vs errors

#### Why show dialog instead of status bar message?
- **Critical error**: Prevents successful save operation
- **Requires user action**: Must add InOuts before saving
- **Clear and visible**: Dialog cannot be missed

### Testing Strategy

#### Unit Tests
- Created `InOutSaveValidationTest.kt` with comprehensive coverage
- Tests all boundary conditions (0, 1, 2, 3+ InOuts)
- Verifies save behavior (success/failure, file creation)

#### Manual Testing (requires full environment)
1. Launch editor: `./gradlew runEditor`
2. Create new network with 0 InOuts
3. Try to save - should show error dialog
4. Add 1 InOut, try to save - should show error dialog
5. Add 2nd InOut, try to save - should succeed

#### CI Testing
- Tests run in CI pipeline with full dependencies
- GitHub Actions provides GITHUB_TOKEN for jDisco dependency
- Integration tests run separately with X11 environment

### Future Enhancements (Out of Scope)

1. **Live validation indicator** - Show InOut count in status bar
2. **Save button state** - Disable when validation would fail
3. **Comprehensive validation framework** (Issue #258)
   - Track connectivity validation
   - Path completeness validation
   - Configuration constraints
   - Warning vs error distinction

### Files Changed

1. `src/main/kotlin/cz/vutbr/fit/interlockSim/gui/MenuBar.kt`
   - Added MIN_INOUT_ELEMENTS constant
   - Added validation logic in performSave()
   - Updated documentation

2. `src/test/kotlin/cz/vutbr/fit/interlockSim/gui/InOutSaveValidationTest.kt` (NEW)
   - 5 comprehensive test cases
   - Extends AbstractFrameTestBase
   - Uses TestContextBuilder for context creation

3. `src/test/kotlin/cz/vutbr/fit/interlockSim/gui/MenuBarTest.kt`
   - Minor formatting fix (no functional changes)

### Related Issues and PRs

- **Issue #80**: This implementation
- **PR #76**: XML validation that required this GUI validation
- **Issue #29**: Related validation requirements
- **Issue #258**: Comprehensive validation framework (future work)

### Verification Checklist

- [x] Validation logic implemented in performSave()
- [x] User-friendly error dialog added
- [x] Error message includes current count
- [x] Save operation blocked when validation fails
- [x] Comprehensive test coverage added
- [x] Tests extend AbstractFrameTestBase
- [x] Tests use TempDir for isolation
- [x] Documentation updated
- [ ] Manual GUI testing (requires full environment)
- [ ] Screenshot of error dialog (requires GUI)

### Author Notes

**Implementation Philosophy:**
This implementation follows the "minimal changes" principle:
- Only adds validation where strictly necessary (save operation)
- Reuses existing validation constant value (2 InOuts)
- Maintains consistency with existing validation (XMLContextFactory)
- Non-intrusive to editing workflow

**Conservative Approach:**
- No changes to EditingContext (allows any InOut count during editing)
- No changes to XMLContextFactory (already has validation)
- No changes to existing tests (only adds new tests)
- No refactoring of unrelated code

**User Experience Focus:**
- Clear, actionable error messages
- Prevents invalid saves (fail-fast)
- Allows flexible editing (validate on save, not continuously)
- Consistent with Issue #258 design decisions

---

**Date:** 2026-02-06
**Author:** GitHub Copilot Agent
**Issue:** #80
**Status:** Implementation Complete, Awaiting CI Verification
