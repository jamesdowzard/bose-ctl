# Independent Device Control — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the phone-relay architecture with direct Mac RFCOMM so both Mac and phone independently control Bose QC Ultra 2 headphones — like having two Bose apps.

**Architecture:** On-demand RFCOMM from both devices. Each opens RFCOMM channel 8, sends BMAP command(s), reads response, closes. No persistent connections, no coordination, no Tailscale dependency. Mac also gets auto-reconnect for brief BT audio dropouts.

**Tech Stack:** Swift + IOBluetooth (Mac), Kotlin + Android BluetoothSocket (phone), blueutil (Mac BT audio)

**Repos:**
- Mac: `~/code/personal/bose/` (BoseCtl.swift, BoseDaemon.swift, hammerspoon/)
- Phone: `~/code/personal/s21/` (app-automation, package au.com.jd.automation.bose)

**Key constants:**
- Headphones MAC: `E4:58:BC:C0:2F:72`
- SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`
- RFCOMM Channel: 8
- BMAP format: `[block, function, operator, length, ...payload]`

---

## Task 1: Mac BMAP Protocol Module (new file)

**Files:**
- Create: `BoseRFCOMM.swift`

**Purpose:** Direct RFCOMM to headphones via IOBluetooth. On-demand: open, send, read, close per command. All BMAP wrappers (battery, ANC, devices, connect, disconnect).

**Step 1: Write BoseRFCOMM.swift**

Core pattern — every command follows this flow:
```swift
func withRFCOMM<T>(_ body: (IOBluetoothRFCOMMChannel) throws -> T) throws -> T {
    let device = IOBluetoothDevice(addressString: BOSE_MAC)!
    var ch: IOBluetoothRFCOMMChannel?
    let status = device.openRFCOMMChannelSync(&ch, withChannelID: RFCOMM_CHANNEL, delegate: self)
    guard status == kIOReturnSuccess, let channel = ch else {
        throw BoseError.connectionFailed
    }
    defer { channel.closeChannel() }
    Thread.sleep(forTimeInterval: 0.3) // drain initial data (Bose firmware quirk)
    return try body(channel)
}
```

BMAP send/receive:
```swift
func sendBMAP(_ channel: IOBluetoothRFCOMMChannel, bytes: [UInt8], timeout: TimeInterval = 3.0) -> [UInt8]? {
    responseData = Data()
    gotResponse = false
    var data = Data(bytes)
    channel.writeSync(&data, length: UInt16(data.count))
    // Wait for delegate callback
    let deadline = Date().addingTimeInterval(timeout)
    while !gotResponse && Date() < deadline {
        RunLoop.current.run(until: Date().addingTimeInterval(0.05))
    }
    return gotResponse ? Array(responseData) : nil
}
```

Delegate for incoming data:
```swift
func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!,
                       data dataPointer: UnsafeMutableRawPointer!, length: Int) {
    responseData.append(Data(bytes: dataPointer, count: length))
    gotResponse = true
}
```

BMAP command wrappers (port from BoseProtocol.kt — same byte sequences):
- `getBattery() -> (level: Int, charging: Bool)?` — send `[0x02, 0x02, 0x01, 0x00]`
- `getAncMode() -> String?` — send `[0x1F, 0x03, 0x01, 0x00]`
- `setAncMode(_ mode: String) -> Bool` — send `[0x1F, 0x03, 0x05, 0x02, {mode}, 0x01]`
- `getActiveDevice() -> [UInt8]?` — send `[0x04, 0x09, 0x01, 0x00]`
- `getConnectedDevices() -> [[UInt8]]` — send `[0x05, 0x01, 0x01, 0x00]`
- `getDeviceInfo(_ mac: [UInt8]) -> DeviceInfo?` — send `[0x04, 0x05, 0x01, 0x06] + mac`
- `getAllDeviceInfo() -> [String: DeviceInfo]` — cross-ref connected with info
- `connectDevice(_ mac: [UInt8]) -> Bool` — send `[0x04, 0x01, 0x05, 0x07, 0x00] + mac`
- `disconnectDevice(_ mac: [UInt8]) -> Bool` — send `[0x04, 0x02, 0x05, 0x06] + mac`
- `switchToDevice(_ mac: [UInt8]) -> Bool` — calls connectDevice
- `getFirmware() -> String?` — send `[0x00, 0x05, 0x01, 0x00]`
- `sendRaw(_ hex: String) -> [UInt8]?`

Device map (same as existing):
```swift
let knownDevices: [(name: String, mac: [UInt8])] = [
    ("phone",  [0xA8, 0x76, 0x50, 0xD3, 0xB1, 0x1B]),
    ("mac",    [0xBC, 0xD0, 0x74, 0x11, 0xDB, 0x27]),
    ("ipad",   [0xF4, 0x81, 0xC4, 0xB5, 0xFA, 0xAB]),
    ("iphone", [0xF8, 0x4D, 0x89, 0xC4, 0xB6, 0xED]),
    ("tv",     [0x14, 0xC1, 0x4E, 0xB7, 0xCB, 0x68]),
]
```

**Step 2: Compile and verify module compiles**

```bash
cd ~/code/personal/bose/.worktrees/independent-device-control
swiftc -O -parse BoseRFCOMM.swift -framework IOBluetooth
```

Expected: no errors (parse-only check).

**Step 3: Commit**

```bash
git add BoseRFCOMM.swift
git commit -m "feat: add direct RFCOMM BMAP protocol module for Mac"
```

---

## Task 2: Rewrite bose-ctl for Direct RFCOMM

**Files:**
- Modify: `BoseCtl.swift`

**Purpose:** Replace daemon socket client with direct RFCOMM calls via BoseRFCOMM. Same CLI interface, same output format — Hammerspoon compatibility preserved.

**Step 1: Rewrite BoseCtl.swift**

Remove: `daemonRequest()`, `DAEMON_SOCKET`, Unix socket code.
Add: instantiate `BoseRFCOMM()` and call methods directly.

Key changes to `runCommand()`:
```swift
let bose = BoseRFCOMM()

switch cmd {
case "status", "s":
    guard let status = bose.getFullStatus() else {
        print("Error: headphones not reachable")
        exit(1)
    }
    // Print in same format as before (Hammerspoon parses this)
    if let active = status.activeDevice {
        print("Active:   \(active)")
    }
    // ... same output format as current BoseCtl.swift lines 125-201 ...

case "battery", "b":
    guard let bat = bose.getBattery() else {
        print("Error: headphones not reachable")
        exit(1)
    }
    print("\(bat.level)%\(bat.charging ? " ⚡" : "")")

case "connect", "c":
    let device = args[2]
    // If connecting Mac, also do blueutil connect for A2DP audio profile
    if device == "mac" {
        _ = runBlueutil(["--connect", BOSE_MAC])
        Thread.sleep(forTimeInterval: 1.5)
    }
    guard bose.switchToDevice(macForName(device)) else {
        print("Error: failed to switch to \(device)")
        exit(1)
    }
    print("Switched to \(device)")

case "disconnect", "d":
    let device = args[2]
    guard bose.disconnectDevice(macForName(device)) else {
        print("Error: failed to disconnect \(device)")
        exit(1)
    }
    if device == "mac" {
        _ = runBlueutil(["--disconnect", BOSE_MAC])
    }
    print("Disconnected \(device)")

case "swap":
    let target = args[2]
    // Disconnect all others except phone, then connect target
    let connected = bose.getConnectedDevices()
    for mac in connected {
        let name = bose.nameForMac(mac)
        if name != target && name != "phone" {
            if name == "mac" { _ = runBlueutil(["--disconnect", BOSE_MAC]) }
            _ = bose.disconnectDevice(mac)
        }
    }
    if target == "mac" {
        _ = runBlueutil(["--connect", BOSE_MAC])
        Thread.sleep(forTimeInterval: 1.5)
    }
    guard bose.switchToDevice(macForName(target)) else {
        print("Error: failed to swap to \(target)")
        exit(1)
    }
    print("Swapped to \(target)")

case "anc":
    if args.count >= 3 {
        guard bose.setAncMode(args[2]) else { print("Error: failed to set ANC"); exit(1) }
        print("ANC: \(args[2])")
    } else {
        guard let mode = bose.getAncMode() else { print("Error: query failed"); exit(1) }
        print("ANC: \(mode)")
    }

case "devices":
    guard let infos = bose.getAllDeviceInfo() else { print("Error: query failed"); exit(1) }
    for (name, info) in infos {
        let state = info.primary ? "●" : (info.connected ? "○" : "·")
        print("  \(state) \(name)\(info.deviceName.isEmpty ? "" : " (\(info.deviceName))")")
    }

case "raw":
    guard let resp = bose.sendRaw(args[2]) else { print("No response"); exit(0) }
    let hex = resp.map { String(format: "%02x", $0) }.joined()
    print("Response (\(resp.count) bytes): \(hex)")
    let ascii = String(bytes: resp.dropFirst(4), encoding: .utf8)?
        .filter { $0.isLetter || $0.isNumber || "._- +/".contains($0) } ?? ""
    if !ascii.isEmpty { print("ASCII: \(ascii)") }

default:
    print("Unknown command: \(cmd)")
    exit(1)
}
```

Keep `runBlueutil()` helper from current code (used for Mac A2DP connect/disconnect).

**Step 2: Build**

```bash
swiftc -O BoseRFCOMM.swift BoseCtl.swift -framework IOBluetooth -o ~/bin/bose-ctl
```

**Step 3: Test each command** (requires headphones on and in range)

```bash
bose-ctl battery
bose-ctl status
bose-ctl anc
bose-ctl devices
bose-ctl swap mac
bose-ctl swap phone
```

Expected: same output format as before, but without needing bosed daemon or phone.

**Step 4: Commit**

```bash
git add BoseCtl.swift
git commit -m "feat: rewrite bose-ctl for direct RFCOMM (no phone relay)"
```

---

## Task 3: Rewrite bosed as Auto-Reconnect Watcher

**Files:**
- Modify: `BoseDaemon.swift`

**Purpose:** Strip everything except BT audio auto-reconnect. When headphones disconnect from Mac briefly (put down for a second), auto-reconnect within a few seconds.

**Step 1: Rewrite BoseDaemon.swift**

Remove: PhoneClient, SubscribeClient, SocketServer, all TCP relay code.
Keep: Logger, blueutil helpers.
Add: Simple poll loop.

```swift
import Foundation

// === Configuration ===
let BOSE_MAC = "E4:58:BC:C0:2F:72"
let BLUEUTIL = "/opt/homebrew/bin/blueutil"
let LOG_PATH = NSHomeDirectory() + "/Library/Logs/bosed.log"
let POLL_INTERVAL: TimeInterval = 3.0
let RECONNECT_WINDOW: TimeInterval = 30.0

// === Logging (keep existing Logger class) ===

// === State ===
var wasConnected = false
var disconnectedAt: Date? = nil

func isBluetoothConnected() -> Bool {
    let proc = Process()
    proc.launchPath = BLUEUTIL
    proc.arguments = ["--is-connected", BOSE_MAC]
    let pipe = Pipe()
    proc.standardOutput = pipe
    do {
        try proc.run()
        proc.waitUntilExit()
    } catch { return false }
    let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(),
                        encoding: .utf8) ?? ""
    return output.trimmingCharacters(in: .whitespacesAndNewlines) == "1"
}

func btConnect() {
    log.log("Auto-reconnect: connecting")
    let proc = Process()
    proc.launchPath = BLUEUTIL
    proc.arguments = ["--connect", BOSE_MAC]
    do {
        try proc.run()
        proc.waitUntilExit()
        log.log("Auto-reconnect: \(proc.terminationStatus == 0 ? "ok" : "failed")")
    } catch {
        log.log("Auto-reconnect: error — \(error.localizedDescription)")
    }
}

// === Main ===
log.log("bosed starting (pid \(ProcessInfo.processInfo.processIdentifier)) — auto-reconnect mode")

signal(SIGTERM) { _ in log.log("SIGTERM"); exit(0) }
signal(SIGINT) { _ in log.log("SIGINT"); exit(0) }

while true {
    let connected = isBluetoothConnected()

    if wasConnected && !connected {
        disconnectedAt = Date()
        log.log("Headphones disconnected — watching for reconnect (30s window)")
    }

    if !connected, let disc = disconnectedAt {
        let elapsed = Date().timeIntervalSince(disc)
        if elapsed < RECONNECT_WINDOW {
            log.log("Auto-reconnect attempt (\(Int(elapsed))s since disconnect)")
            btConnect()
            Thread.sleep(forTimeInterval: 2.0)
            if isBluetoothConnected() {
                log.log("Auto-reconnect successful")
                disconnectedAt = nil
            }
        } else {
            log.log("Reconnect window expired — stopping")
            disconnectedAt = nil
        }
    }

    wasConnected = connected || (disconnectedAt != nil && isBluetoothConnected())
    Thread.sleep(forTimeInterval: POLL_INTERVAL)
}
```

**Step 2: Build**

```bash
swiftc -O BoseDaemon.swift -o ~/bin/bosed
```

**Step 3: Restart daemon**

```bash
launchctl unload ~/Library/LaunchAgents/com.jamesdowzard.bosed.plist
launchctl load ~/Library/LaunchAgents/com.jamesdowzard.bosed.plist
```

**Step 4: Test auto-reconnect**

1. Connect headphones: `blueutil --connect E4:58:BC:C0:2F:72`
2. Disconnect briefly: `blueutil --disconnect E4:58:BC:C0:2F:72`
3. Watch log: `tail -f ~/Library/Logs/bosed.log`
4. Expected: reconnects within 3-5 seconds

**Step 5: Commit**

```bash
git add BoseDaemon.swift
git commit -m "feat: rewrite bosed as simple auto-reconnect watcher"
```

---

## Task 4: Raycast Toggle Script

**Files:**
- Create: `raycast/bose-toggle.sh`

**Step 1: Create toggle script**

```bash
#!/bin/bash
# @raycast.schemaVersion 1
# @raycast.title Bose Toggle
# @raycast.mode compact
# @raycast.icon 🎧
# @raycast.packageName Bose

BOSE_MAC="E4:58:BC:C0:2F:72"
BLUEUTIL="/opt/homebrew/bin/blueutil"
BOSE_CTL="$HOME/bin/bose-ctl"

connected=$($BLUEUTIL --is-connected "$BOSE_MAC")
if [ "$connected" = "1" ]; then
    $BLUEUTIL --disconnect "$BOSE_MAC"
    echo "Disconnected"
else
    $BLUEUTIL --connect "$BOSE_MAC"
    sleep 2
    battery=$($BOSE_CTL battery 2>/dev/null)
    echo "Connected ${battery}"
fi
```

**Step 2: Install**

```bash
mkdir -p raycast
chmod +x raycast/bose-toggle.sh
~/bin/raycast-install.sh raycast/bose-toggle.sh
```

**Step 3: Commit**

```bash
git add raycast/
git commit -m "feat: add Raycast bose toggle script"
```

---

## Task 5: Phone — On-Demand BoseProtocol

**Files:**
- Modify: `~/code/personal/s21/app-automation/src/main/java/au/com/jd/automation/bose/BoseProtocol.kt`

**Purpose:** Switch from persistent RFCOMM to on-demand. Each operation opens socket, sends, reads, closes.

**Step 1: Add withConnection pattern**

```kotlin
@Synchronized
fun <T> withConnection(block: () -> T): T? {
    try {
        if (!isConnected && !connect()) return null
        val result = block()
        return result
    } finally {
        disconnect()
    }
}
```

**Step 2: Wrap all public methods**

Every public method that sends BMAP commands gets wrapped:
```kotlin
fun getBattery(): BatteryInfo? = withConnection {
    val resp = send(byteArrayOf(0x02, 0x02, OP_GET, 0x00)) ?: return@withConnection null
    if (resp.size < 5 || resp[2] != OP_RESP) return@withConnection null
    val level = resp[4].toInt() and 0xFF
    val charging = if (resp.size >= 8) (resp[7].toInt() and 0xFF) != 0 else false
    BatteryInfo(level = level.coerceIn(0, 100), charging = charging)
}
```

Apply same pattern to: `getActiveDevice`, `getConnectedDevices`, `getAllDeviceInfo`, `getAncMode`, `setAncMode`, `connectDevice`, `disconnectDevice`, `switchToDevice`, `getProductName`, `getFirmware`, `getPairedDevices`, `getDeviceInfo`, `setPairingMode`.

Note: `send()` stays internal (called within withConnection). `connect()` and `disconnect()` stay as-is.

**Step 3: Commit**

```bash
cd ~/code/personal/s21
git add app-automation/src/main/java/au/com/jd/automation/bose/BoseProtocol.kt
git commit -m "feat(bose): switch to on-demand RFCOMM connections"
```

---

## Task 6: Phone — Simplify BoseService (Remove Mac Relay)

**Files:**
- Modify: `~/code/personal/s21/app-automation/src/main/java/au/com/jd/automation/bose/BoseService.kt`

**Step 1: Delete Mac relay code**

Remove entirely:
- Fields: `macSocket`, `macSocketLock`, `heartbeatExecutor`, `heartbeatFuture`
- Methods: `startMacHeartbeat()`, `stopMacHeartbeat()`, `pushToMac()`
- Subscribe handling in `handleTcpClient()` (the if block for cmd == "subscribe" that keeps socket open)
- `"subscribe"` case in `processTcpCommand()`
- All `pushToMac()` calls in `switchDevice()` (lines 249-259) and `swapDeviceJson()` (lines 798-806)
- Heartbeat cleanup in `onDestroy()`

**Step 2: Remove ensureConnected()**

With on-demand BoseProtocol, each call handles its own connection. Remove `ensureConnected()` method. In TCP command handlers, just call BoseProtocol methods directly — they return null on failure.

**Step 3: Simplify switchDevice()**

```kotlin
private fun switchDevice(deviceName: String) {
    val mac = BoseProtocol.DEVICES[deviceName]
        ?: run { broadcastError("Unknown device: $deviceName"); return }

    val success = BoseProtocol.switchToDevice(mac) ?: false
    if (success) {
        updateSlots(deviceName)
        broadcastCurrentState()
        updateNotification("$deviceName active")
    } else {
        broadcastError("Failed to switch to $deviceName")
    }
}
```

**Step 4: Simplify swapDeviceJson()**

Same — remove pushToMac calls, remove Thread.sleep waits. Just disconnect others and connect target via BMAP on-demand calls.

**Step 5: Commit**

```bash
git add app-automation/src/main/java/au/com/jd/automation/bose/BoseService.kt
git commit -m "feat(bose): remove Mac relay, simplify to on-demand RFCOMM"
```

---

## Task 7: Phone — Simplify BoseConnectionManager

**Files:**
- Modify: `~/code/personal/s21/app-automation/src/main/java/au/com/jd/automation/bose/BoseConnectionManager.kt`

**Step 1: Reduce to BT event monitor**

Remove: State machine (DISCONNECTED/CONNECTING/CONNECTED/RECONNECTING), `attemptConnect()`, exponential backoff, safety poll, `onConnectionLost()`.

Keep: BroadcastReceiver for ACL events, `start()`/`stop()`, `pause()`/`resume()`.

```kotlin
class BoseConnectionManager(
    private val context: Context,
    private val onHeadphonesStateChanged: (nearby: Boolean, reason: String) -> Unit
) {
    @Volatile var headphonesNearby = false
        private set

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address != BoseProtocol.BOSE_MAC) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    headphonesNearby = true
                    onHeadphonesStateChanged(true, "headphones connected")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    headphonesNearby = false
                    onHeadphonesStateChanged(false, "headphones disconnected")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(btReceiver, filter)
    }

    fun stop() {
        try { context.unregisterReceiver(btReceiver) } catch (_: Exception) {}
    }
}
```

**Step 2: Update BoseService.kt** to match new ConnectionManager API

Replace `connectionManager` callbacks — instead of 4-state handling, just update notification on nearby/not-nearby.

**Step 3: Commit**

```bash
git add app-automation/src/main/java/au/com/jd/automation/bose/BoseConnectionManager.kt
git add app-automation/src/main/java/au/com/jd/automation/bose/BoseService.kt
git commit -m "feat(bose): simplify connection manager to event monitor"
```

---

## Task 8: Phone — Build and Deploy

**Step 1: Build**

```bash
cd ~/code/personal/s21
./gradlew :app-automation:assembleDebug
```

**Step 2: Deploy**

```bash
adb install -r app-automation/build/outputs/apk/debug/app-automation-debug.apk
```

**Step 3: Test**

- Open Bose tab in automation app — verify device buttons work
- Widget should update on device switch
- Put headphones down and pick up — verify no issues

---

## Task 9: Cleanup

**Step 1: Remove old BT pairings** (confirm with user first)

```bash
blueutil --unpair 04-52-C7-33-D0-77  # Old QC35
blueutil --unpair 28-11-A5-DB-34-12  # verBose (test device)
blueutil --unpair E4-58-BC-2B-0E-72  # Carlo Verbosi (duplicate)
```

**Step 2: Verify Hammerspoon panel**

Press `⌥B` — panel should work exactly as before since bose-ctl output format is preserved.

---

## Task 10: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

Replace architecture section with:

```markdown
## Architecture (Independent Control)

Both Mac and phone control headphones independently via on-demand RFCOMM.
No persistent connections. No coordination. No Tailscale dependency.

Mac: bose-ctl → IOBluetooth RFCOMM → Headphones (direct)
Phone: BoseService → Android RFCOMM → Headphones (direct)

bosed daemon watches for brief BT audio disconnects and auto-reconnects
via blueutil (30-second window, then stops trying).
```

Update component list, remove phone relay rules, keep safety rules.

**Commit:**

```bash
git add CLAUDE.md
git commit -m "docs: update architecture for independent device control"
```

---

## Build Commands Reference

```bash
# Mac — bose-ctl (needs IOBluetooth)
swiftc -O BoseRFCOMM.swift BoseCtl.swift -framework IOBluetooth -o ~/bin/bose-ctl

# Mac — bosed (no IOBluetooth needed)
swiftc -O BoseDaemon.swift -o ~/bin/bosed

# Phone — build and deploy
cd ~/code/personal/s21
./gradlew :app-automation:assembleDebug
adb install -r app-automation/build/outputs/apk/debug/app-automation-debug.apk
```

## Task Dependencies

```
Task 1 (BoseRFCOMM.swift) → Task 2 (bose-ctl) → Task 3 (bosed) → Task 4 (Raycast)
                                                                  → Task 9 (Cleanup)
                                                                  → Task 10 (Docs)

Task 5 (BoseProtocol.kt) → Task 6 (BoseService.kt) → Task 7 (ConnectionManager) → Task 8 (Deploy)

Mac tasks (1-4) and Phone tasks (5-8) are INDEPENDENT — can run in parallel.
```

## Safety Rules (Unchanged)

- **NEVER unpair headphones or toggle pairing mode without explicit user approval**
- **Single RFCOMM attempt per command** — no retry loops
- **getConnectedDevices() is ground truth** — not getDeviceInfo status byte
- **Bose Music app must stay disabled** — `adb shell pm disable-user com.bose.bosemusic`
- **Drain 300ms of initial data** after RFCOMM connect (Bose firmware quirk)
- **Verify BMAP with firmware query** after connect — `[0x00, 0x05, 0x01, 0x00]`
