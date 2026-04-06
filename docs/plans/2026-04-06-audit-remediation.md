# Audit Remediation Plan

**Date:** 2026-04-06
**Goal:** Fix correctness bugs, remove dead code, refactor for clarity, update docs — based on 5-agent audit

## Phase 1: Critical Correctness (sequential)

| Task | Files | Deps |
|------|-------|------|
| 1A. Fix `withConnection` ignoring connect failure | `BoseProtocol.kt:158` | — |
| 1B. Fix multipoint operator disagreement | `BoseProtocol.kt:536`, `BoseRFCOMM.swift:708` | — |
| 1C. Fix `connectDevice` response check disagreement | `BoseProtocol.kt:387`, `BoseRFCOMM.swift:429` | — |
| 1D. Add Quest to macOS popover | `PopoverView.swift:23` | — |
| 1E. Add `setDeviceName` length guard on Android | `BoseProtocol.kt:516` | — |

**Commit:** `fix: correctness — withConnection failure, operator alignment, Quest on macOS, name length guard`

## Phase 2: Dead Code Removal (parallel)

| Task | Files | Deps |
|------|-------|------|
| 2A. Delete `DEVICE_MACS` | `BoseProtocol.kt:55-62` | — |
| 2B. Delete dead Swift functions | `BoseRFCOMM.swift`, `BoseManager.swift` | — |
| 2C. Delete dead Android functions | `BoseProtocol.kt` | — |
| 2D. Move `OP_SET_GET` to module constants | `BoseRFCOMM.swift`, `BoseProtocol.kt` | — |
| 2E. Fix stale CLI EQ error | `BoseCtl.swift` | — |
| 2F. Fix RFCOMM channel comment | `BoseRFCOMM.swift` | — |

**Commit:** `chore: remove dead code, fix stale comments and errors`

## Phase 3: Refactoring (sequential)

| Task | Files | Deps |
|------|-------|------|
| 3A. Extract `getStringResponse()` in BoseProtocol.kt | `BoseProtocol.kt:404-468` | 2C |
| 3B. Extract `command()` helper in BoseViewModel.kt | `BoseViewModel.kt:218-310` | — |
| 3C. Batch StateFlow emissions in refreshAll() | `BoseViewModel.kt:67-196` | 3B |
| 3D. Extract `getStringField()` in BoseRFCOMM.swift | `BoseRFCOMM.swift:461-644` | 2B |

**Commit:** `refactor: extract helpers, batch state emissions, reduce duplication`

## Phase 4: Documentation

| Task | Files | Deps |
|------|-------|------|
| 4A. Fix CLAUDE.md | `CLAUDE.md` | 1B, 2A-2F |

Fixes: Volume/Multipoint operator rows, add Block 0x05 section, clarify StandbyTimer vs auto-off, reorder Device Map, add BoseTileService/DevicePickerActivity to components, note FOREGROUND_SERVICE_CONNECTED_DEVICE.

**Commit:** `docs: fix BMAP operator table, add block 0x05, clarify components`

## Phase 5: Performance + Infra (optional)

| Task | Files | Deps |
|------|-------|------|
| 5A. Reduce getDeviceInfo loop exposure | `BoseService.kt:283` | — |
| 5B. Move LaunchAgent logs to ~/Library/Logs | `com.jamesdowzard.bose-control.plist` | — |
| 5C. Fix PopoverView hardcoded MAC | `PopoverView.swift:545` | — |
| 5D. Fix isRefreshing stuck on nil bose | `BoseManager.swift:160` | — |

**Commit:** `fix: perf — reduce device info timeout, move logs, fix stuck spinner`

## Summary

- **20 tasks** across 5 phases
- Phase 1: 5 tasks, sequential (correctness)
- Phase 2: 6 tasks, parallel (dead code)
- Phase 3: 4 tasks, sequential (refactoring)
- Phase 4: 1 task (docs)
- Phase 5: 4 tasks, parallel (perf/infra)
- **Complexity:** Medium
- **Risk:** Low — verify operator changes with device
