# InOut Validation Flow Diagram

## Issue #80: GUI Validation Implementation

### Validation Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    User Action: Save                         │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
        ┌────────────────────────────────────┐
        │  MenuBar.SaveAction.actionPerformed │
        └────────────┬───────────────────────┘
                     │
                     ▼
        ┌────────────────────────────────────┐
        │    MenuBar.performSave(file)       │
        └────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────────────────┐
│  Validation Check:                                      │
│  inOutCount = editingContext.getInOuts().size          │
│  if (inOutCount < MIN_INOUT_ELEMENTS)                  │
└────────────┬───────────────────────┬───────────────────┘
             │                       │
             │ FAIL                  │ PASS
             │ (< 2 InOuts)         │ (>= 2 InOuts)
             ▼                       ▼
    ┌─────────────────┐    ┌─────────────────────┐
    │ Show Error      │    │ saveContext(...)    │
    │ Dialog          │    └──────────┬──────────┘
    └────────┬────────┘               │
             │                        ▼
             │               ┌─────────────────────┐
             │               │ Update Tracker      │
             │               │ Show Success Msg    │
             │               └──────────┬──────────┘
             │                          │
             ▼                          ▼
    ┌─────────────────┐    ┌─────────────────────┐
    │ return false    │    │ return true         │
    │ (save blocked)  │    │ (save succeeded)    │
    └─────────────────┘    └─────────────────────┘
```

### Error Dialog Details

```
┌────────────────────────────────────────────────────────────┐
│  ⚠ Cannot Save - Insufficient InOut Elements               │
├────────────────────────────────────────────────────────────┤
│                                                             │
│  Railway network must have at least 2 InOut elements       │
│  (entry and exit points).                                  │
│                                                             │
│  Current count: 1                                          │
│                                                             │
│  InOut elements define where trains enter and exit the     │
│  railway network.                                          │
│  Please add more InOut elements before saving.             │
│                                                             │
│                        ┌────────┐                          │
│                        │   OK   │                          │
│                        └────────┘                          │
└────────────────────────────────────────────────────────────┘
```

### Validation Logic

```kotlin
// Constants
MIN_INOUT_ELEMENTS = 2  // Defined in MenuBar companion object

// Validation Check
val inOutCount = editingContext.getInOuts().size
if (inOutCount < MIN_INOUT_ELEMENTS) {
    // Show error dialog
    JOptionPane.showMessageDialog(...)
    return false  // Block save operation
}

// Proceed with save if validation passes
val success = editingContextFactory.saveContext(editingContext, file)
return success
```

### Save Operation Entry Points

All save operations go through `performSave()`:

1. **File > Save** → `SaveAction.actionPerformed()`
   - If file exists: calls `performSave(currentFile)`
   - If no file: delegates to SaveAsAction

2. **File > Save As...** → `SaveAsAction.actionPerformed()`
   - Shows file chooser
   - Calls `performSave(selectedFile)`

3. **Window Close with Unsaved Changes** → `Frame.saveAndExit()`
   - Calls `menuBar.triggerSave()`
   - Which calls `performSave(currentFile or show dialog)`

**Result:** All save paths are validated ✅

### Test Coverage Matrix

| InOut Count | Expected Result | Test Case                        | Status |
|-------------|-----------------|----------------------------------|--------|
| 0           | FAIL (blocked)  | contextWith0InOutsCannotBeSaved | ✅     |
| 1           | FAIL (blocked)  | contextWith1InOutCannotBeSaved  | ✅     |
| 2           | PASS (saved)    | contextWith2InOutsCanBeSaved    | ✅     |
| 3+          | PASS (saved)    | contextWith3InOutsCanBeSaved    | ✅     |

### Integration with Existing Validation

```
┌────────────────────────────────────────────────────────────┐
│                    Validation Timeline                      │
└────────────────────────────────────────────────────────────┘

1. EDITING PHASE (EditingContext)
   - No validation
   - User can create incomplete networks
   - InOut count can be 0, 1, 2, 3+
   - Flexible for work-in-progress

2. SAVE OPERATION (MenuBar.performSave) ← NEW VALIDATION HERE
   ✅ Validates InOut count >= 2
   ✅ Shows error dialog if validation fails
   ✅ Blocks save operation
   
3. FILE WRITE (EditingContextFactory.saveContext)
   - Writes XML to disk
   - No additional validation (already validated)

4. FILE LOAD (XMLContextFactory.createContext)
   ✅ Validates InOut count >= 2 (existing validation from PR #76)
   ✅ Throws ContextCreationException if invalid
   - Ensures saved files meet requirements

Result: Two-level validation
- GUI validation: Prevents invalid saves (user-friendly)
- XML validation: Ensures loaded files are valid (safety net)
```

### Benefits

✅ **Fail-Fast**: Error shown at save time, not reload time
✅ **User-Friendly**: Clear error message with actionable guidance
✅ **Consistent**: Matches XMLContextFactory validation rules
✅ **Non-Intrusive**: Doesn't block editing, only saving
✅ **Comprehensive**: Applies to all save operations
✅ **Well-Tested**: 5 test cases covering all scenarios

### Design Principles Applied

1. **Minimal Changes** - Only modified performSave() method
2. **Single Responsibility** - Validation logic in one place
3. **DRY** - Constant MIN_INOUT_ELEMENTS defined once
4. **Fail-Fast** - Validate before attempting save
5. **User Experience** - Clear, actionable error messages
6. **Test Coverage** - Comprehensive unit tests

### Future Enhancements (Out of Scope)

- [ ] Real-time InOut count indicator in status bar
- [ ] Save button state management (disable when invalid)
- [ ] Comprehensive validation framework (Issue #258)
- [ ] Warning vs Error distinction
- [ ] Batch validation with multiple error display

---

**Date:** 2026-02-06
**Issue:** #80
**Implementation:** Complete
