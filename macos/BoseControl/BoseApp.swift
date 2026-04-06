/// BoseControl: Native macOS app for Bose QC Ultra 2

import SwiftUI

@main
struct BoseControlApp: App {
    @StateObject private var manager = BoseManager()

    var body: some Scene {
        WindowGroup {
            ContentView(manager: manager)
                .onAppear { manager.startPolling() }
        }
        .defaultSize(width: 380, height: 600)
    }
}
