# Issue #80: GUI InOut Validation

**Status:** Implemented (2026-02-06)
**Branch:** `copilot/add-gui-validation-in-editor`

## Problem

Users could save railway networks with 0 InOut elements through the GUI editor. The file was written successfully, but could not be reloaded (XML validation in XMLContextFactory requires >= 1 InOut since PR #356 bidirectional operation). Result: confusing "saved but can't be loaded" experience.

## Implementation

### Validation method — `MenuBar.validateForSave(context)`

A pure companion-object function added to `MenuBar`:

```kotlin
fun validateForSave(context: EditingContext): Boolean =
    context.getInOuts().size >= XMLContextFactory.MIN_INOUT_ELEMENTS
```

### Pre-save check — `MenuBar.performSave()`

Called before every save attempt. On failure, shows an error dialog and returns `false`:

```
Cannot Save - Insufficient InOut Elements
Railway network must have at least 1 InOut entry/exit point.
Current count: 0
InOut entry/exit points define where trains enter and exit the railway network.
Please add at least one InOut entry/exit point before saving.
```

All three save paths go through `performSave()`:
1. File > Save → `SaveAction`
2. File > Save As... → `SaveAsAction`
3. Window close → `triggerSave()`

### Constant alignment

`MenuBar` uses `XMLContextFactory.MIN_INOUT_ELEMENTS` (= 1) — no separate constant. This avoids drift between the GUI and XML validation layers.

## Validation flow

```
User: Save
  -> MenuBar.performSave(file)
      -> validateForSave(context)
          -> false: show error dialog, return false (save blocked)
          -> true:  editingContextFactory.saveContext(...)
                       -> success: update tracker, show status bar message
                       -> failure: show IO error dialog
```

## Test coverage

`InOutSaveValidationTest` (4 unit tests, no EDT, no reflection):

| InOut count | Expected | Test                         |
|-------------|----------|------------------------------|
| 0           | false    | `contextWith0InOutsCannotBeSaved` |
| 1           | true     | `contextWith1InOutCanBeSaved`     |
| 2           | true     | `contextWith2InOutsCanBeSaved`    |
| 3+          | true     | `contextWith3InOutsCanBeSaved`    |

Tests extend `KoinTestBase` and call `MenuBar.validateForSave(context)` directly.

## Related

- PR #356 — bidirectional train operation (allows single-InOut networks)
- XMLContextFactory — enforces same constraint at XML load time
- Issue #258 — future comprehensive validation framework
