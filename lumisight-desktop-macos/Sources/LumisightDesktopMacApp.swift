import SwiftUI

@main
struct LumisightDesktopMacApp: App {
    @StateObject private var store = DesktopStore()

    var body: some Scene {
        WindowGroup("Lumisight") {
            ContentView()
                .environmentObject(store)
                .frame(minWidth: 1320, minHeight: 840)
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
        }
    }
}
