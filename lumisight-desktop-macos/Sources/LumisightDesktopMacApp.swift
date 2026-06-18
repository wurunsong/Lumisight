import SwiftUI
import AppKit

@main
struct LumisightDesktopMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var store = DesktopStore()

    var body: some Scene {
        WindowGroup("Lumisight") {
            ContentView()
                .environmentObject(store)
                .frame(minWidth: 1320, minHeight: 840)
                .preferredColorScheme(.dark)
        }
        .windowResizability(.contentSize)

        Window("Connection Console", id: "connection-console") {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(store.connectionLog, id: \.self) { line in
                        Text(line)
                            .font(.system(.caption, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            .frame(minWidth: 720, minHeight: 420)
            .preferredColorScheme(.dark)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)
            NSApp.windows.first?.makeKeyAndOrderFront(nil)
        }
    }
}
