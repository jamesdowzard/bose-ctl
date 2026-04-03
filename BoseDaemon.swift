/// bosed: Auto-reconnect watcher for Bose QC Ultra 2 headphones
/// Polls blueutil for BT connection status. On brief disconnects (<30s),
/// automatically attempts reconnection. Longer disconnects are treated
/// as intentional (user powered off or walked away).

import Foundation

// === Configuration ===
let BOSE_MAC = "E4:58:BC:C0:2F:72"
let BLUEUTIL = "/opt/homebrew/bin/blueutil"
let LOG_PATH = NSHomeDirectory() + "/Library/Logs/bosed.log"
let POLL_INTERVAL: TimeInterval = 3.0
let RECONNECT_WINDOW: TimeInterval = 30.0

// === Logging ===
class Logger {
    static let shared = Logger()
    private let handle: FileHandle?
    private let formatter: DateFormatter

    private init() {
        formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"

        FileManager.default.createFile(atPath: LOG_PATH, contents: nil)
        handle = FileHandle(forWritingAtPath: LOG_PATH)
        handle?.seekToEndOfFile()
    }

    func log(_ msg: String) {
        let ts = formatter.string(from: Date())
        let line = "[\(ts)] \(msg)\n"
        handle?.write(line.data(using: .utf8) ?? Data())
        print(line, terminator: "")
        fflush(stdout)
    }
}

let log = Logger.shared

// === Bluetooth Helpers ===
func isBluetoothConnected() -> Bool {
    let proc = Process()
    let pipe = Pipe()
    proc.launchPath = BLUEUTIL
    proc.arguments = ["--is-connected", BOSE_MAC]
    proc.standardOutput = pipe
    proc.standardError = FileHandle.nullDevice
    do {
        try proc.run()
        proc.waitUntilExit()
    } catch {
        log.log("blueutil --is-connected error: \(error.localizedDescription)")
        return false
    }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    let output = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return output == "1"
}

func btConnect() {
    log.log("Attempting reconnect...")
    let proc = Process()
    proc.launchPath = BLUEUTIL
    proc.arguments = ["--connect", BOSE_MAC]
    do {
        try proc.run()
        proc.waitUntilExit()
        let ok = proc.terminationStatus == 0
        log.log("Reconnect \(ok ? "command sent" : "failed (blueutil exit \(proc.terminationStatus))")")
    } catch {
        log.log("Reconnect error: \(error.localizedDescription)")
    }
}

// === State ===
var wasConnected = false
var disconnectedAt: Date? = nil

// === Main ===
log.log("bosed starting (pid \(ProcessInfo.processInfo.processIdentifier)) — auto-reconnect mode")

// Signal handlers for clean shutdown
signal(SIGTERM) { _ in
    log.log("Received SIGTERM, shutting down")
    exit(0)
}
signal(SIGINT) { _ in
    log.log("Received SIGINT, shutting down")
    exit(0)
}

// Check initial state
wasConnected = isBluetoothConnected()
log.log("Initial state: headphones \(wasConnected ? "connected" : "not connected")")

while true {
    let connected = isBluetoothConnected()

    if wasConnected && !connected {
        // Just disconnected — start reconnect window
        disconnectedAt = Date()
        log.log("Headphones disconnected — watching for reconnect (\(Int(RECONNECT_WINDOW))s window)")
    }

    if let disconnectTime = disconnectedAt {
        if connected {
            // Reconnected within window
            let elapsed = Date().timeIntervalSince(disconnectTime)
            log.log("Headphones reconnected after \(String(format: "%.1f", elapsed))s")
            disconnectedAt = nil
        } else if Date().timeIntervalSince(disconnectTime) >= RECONNECT_WINDOW {
            // Window expired — stop trying
            log.log("Reconnect window expired (\(Int(RECONNECT_WINDOW))s) — assuming intentional disconnect")
            disconnectedAt = nil
        } else {
            // Still within window — try reconnecting
            btConnect()
        }
    }

    wasConnected = connected
    Thread.sleep(forTimeInterval: POLL_INTERVAL)
}
