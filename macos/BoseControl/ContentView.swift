/// ContentView: Frosted-dark two-panel layout for Bose headphone control.
/// Left panel = status sidebar (220px), right panel = device grid + EQ.
/// Uses NSVisualEffectView for macOS vibrancy/translucency.

import SwiftUI

// MARK: - Theme Colors

/// No neon green. White/light grey for active, warm grey for secondary, 40% opacity grey for offline.
private let activeColor = Color.white
private let secondaryColor = Color(white: 0.55)
private let offlineColor = Color(white: 0.4)
private let cardColor = Color.white.opacity(0.06)
private let dividerColor = Color.white.opacity(0.08)

// MARK: - Device Button Model

private struct DeviceButton: Identifiable {
    let id: String  // name key
    let label: String
    let symbol: String
}

private let deviceButtons: [DeviceButton] = [
    DeviceButton(id: "mac", label: "Mac", symbol: "laptopcomputer"),
    DeviceButton(id: "phone", label: "Phone", symbol: "iphone"),
    DeviceButton(id: "ipad", label: "iPad", symbol: "ipad"),
    DeviceButton(id: "iphone", label: "iPhone", symbol: "iphone"),
    DeviceButton(id: "tv", label: "TV", symbol: "tv"),
    DeviceButton(id: "quest", label: "Quest", symbol: "visionpro"),
]

// MARK: - Visual Effect Background

/// NSViewRepresentable wrapping NSVisualEffectView with .hudWindow material
/// for macOS dark vibrancy/translucency.
struct VisualEffectBackground: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = .hudWindow
        view.blendingMode = .behindWindow
        view.state = .active
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}

// MARK: - Main View

struct ContentView: View {
    @ObservedObject var manager: BoseManager

    var body: some View {
        Group {
            if manager.isConnected {
                connectedLayout
            } else {
                disconnectedView
            }
        }
        .frame(width: 640, height: 360)
        .background(VisualEffectBackground())
        .onAppear {
            manager.refreshState()
        }
    }

    // MARK: - Connected Layout

    private var connectedLayout: some View {
        HStack(spacing: 0) {
            // Left panel — status sidebar
            leftPanel
                .frame(width: 220)

            // Divider
            Rectangle()
                .fill(dividerColor)
                .frame(width: 1)

            // Right panel — device grid + EQ
            rightPanel
                .frame(maxWidth: .infinity)
        }
        .padding(.top, 28)  // clear transparent title bar
    }

    // MARK: - Left Panel (Status Sidebar)

    private var leftPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Device name + battery
            VStack(alignment: .leading, spacing: 4) {
                Text(manager.deviceName)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(activeColor)

                HStack(spacing: 6) {
                    Image(systemName: batteryIcon)
                        .font(.system(size: 11))
                        .foregroundColor(batteryColor)
                    Text("\(manager.batteryLevel)%")
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundColor(batteryColor)
                    if manager.batteryCharging {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 10))
                            .foregroundColor(.orange)
                    }
                }
            }

            // ANC mode
            VStack(alignment: .leading, spacing: 4) {
                Text("NOISE CONTROL")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(secondaryColor)
                    .tracking(1)
                Text(manager.ancModeName)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(activeColor)
            }

            // Volume
            VStack(alignment: .leading, spacing: 4) {
                Text("VOLUME")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(secondaryColor)
                    .tracking(1)
                Text("\(manager.volume) / \(manager.volumeMax)")
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundColor(activeColor)
            }

            // Wear detection
            VStack(alignment: .leading, spacing: 4) {
                Text("STATUS")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(secondaryColor)
                    .tracking(1)
                HStack(spacing: 4) {
                    Circle()
                        .fill(manager.onHead ? Color.green : Color.orange)
                        .frame(width: 6, height: 6)
                    Text(manager.onHead ? "On head" : "Off head")
                        .font(.system(size: 12))
                        .foregroundColor(activeColor)
                }
            }

            Spacer()
        }
        .padding(16)
    }

    // MARK: - Right Panel (Device Grid + EQ Placeholder)

    private var rightPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Devices
            Text("DEVICES")
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(secondaryColor)
                .tracking(1)

            Text("Device grid placeholder")
                .font(.system(size: 12))
                .foregroundColor(secondaryColor)

            Spacer()

            // EQ
            Text("EQUALIZER")
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(secondaryColor)
                .tracking(1)

            Text("EQ controls placeholder")
                .font(.system(size: 12))
                .foregroundColor(secondaryColor)

            Spacer()
        }
        .padding(16)
    }

    // MARK: - Disconnected View

    private var disconnectedView: some View {
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: "headphones")
                .font(.system(size: 48, weight: .thin))
                .foregroundColor(offlineColor)

            Text("Not Connected")
                .font(.system(size: 15, weight: .medium))
                .foregroundColor(secondaryColor)

            Button(action: {
                manager.connectDevice("mac")
            }) {
                Text("Connect")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.black)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 8)
                    .background(activeColor)
                    .cornerRadius(8)
            }
            .buttonStyle(.plain)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.top, 28)
    }

    // MARK: - Helpers

    private var batteryIcon: String {
        switch manager.batteryLevel {
        case 0..<10: return "battery.0"
        case 10..<35: return "battery.25"
        case 35..<65: return "battery.50"
        case 65..<90: return "battery.75"
        default: return "battery.100"
        }
    }

    private var batteryColor: Color {
        if manager.batteryCharging { return .green }
        if manager.batteryLevel < 15 { return .red }
        if manager.batteryLevel < 30 { return .orange }
        return activeColor
    }
}
