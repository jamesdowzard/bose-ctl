# BLE GATT Transport for EQ, Immersion, Auto-Off

**Date:** 2026-04-05

## Problem

The QC Ultra 2 uses two Bluetooth transports. RFCOMM handles most controls (ANC, volume, connect, media, name, multipoint). But EQ, immersion level, and auto-off timer return error 0x04 when SET over RFCOMM. These require BLE GATT writes.

## Architecture

Dual transport — RFCOMM stays for everything it handles, BLE GATT added only for the 3 settings that need it.

```
┌──────────────┐
│  BoseManager │
│      │       │
│  ┌───┴───┐   │
│ RFCOMM  GATT │
│  │       │   │
└──┼───────┼───┘
   │       │
   ▼       ▼
┌──────────────┐
│  QC Ultra 2  │
│ SPP ← RFCOMM │  ANC, volume, connect, media, name, multipoint
│ BLE ← GATT   │  EQ, immersion, auto-off
└──────────────┘
```

## GATT Services (scanned 2026-04-05)

| Service | Writable Characteristics |
|---------|------------------------|
| Battery | D417C028 (RWwN), C65B8F2F (RWwN), FE2C1234 (WI), FE2C1235 (WI), FE2C1236 (W), FE2C1237 (WI), FE2C1238 (RWN) |

FE2C12XX Write+Indicate characteristics are the most likely BMAP-over-BLE command channels.

## Probe Strategy

1. Connect via CoreBluetooth
2. Read current EQ over RFCOMM (baseline)
3. Subscribe to indications on all writable characteristics
4. Write BMAP EQ SET bytes to each writable characteristic, one at a time
5. Check which returns ACK (not error)
6. Verify EQ changed via RFCOMM read-back
7. Reset EQ to original values
8. Hardcode the working characteristic UUID

## RFCOMM SET commands (the BMAP bytes to send over GATT)

| Setting | BMAP bytes |
|---------|-----------|
| EQ | `01,07,06,0C,F6,0A,{bass},00,F6,0A,{mid},01,F6,0A,{treble},02` |
| Immersion | TBD (probe needed) |
| Auto-off | TBD (probe needed) |

Values are signed bytes (-10 to +10 for EQ bands).

## UI

### EQ Section (below ANC, above Volume)

Preset buttons: Flat (0/0/0), Bass Boost (+6/0/-2), Treble Boost (-2/0/+6), Vocal (-2/+4/+2)

Three sliders: Bass, Mid, Treble (-10 to +10). Send GATT write on slider release.

### Settings Section

- Immersion: slider 0-10 for custom ANC depth
- Auto-off: picker (Never, 5min, 20min, 40min, 60min, 180min)

## Platforms

- Mac: CoreBluetooth (CBCentralManager, CBPeripheral)
- Android: BluetoothGatt (BluetoothGattCallback)

Both use on-demand BLE connections (connect, write, disconnect). No persistent BLE connection.

## Error Handling

- BLE unavailable or connection fails: EQ/immersion/auto-off remain read-only (current behavior)
- GATT write fails: show brief error, don't crash
- BLE and RFCOMM don't run simultaneously — separate command paths

## Phases

1. **Probe**: standalone Swift script to identify correct GATT characteristic
2. **BoseGATT module**: Mac (BoseGATT.swift) + Android (BoseGatt.kt) — connect, write EQ/immersion/auto-off
3. **UI**: EQ sliders + presets, immersion slider, auto-off picker in both apps
