import SwiftUI
import WidgetKit

struct RecorderEntry: TimelineEntry {
    let date: Date
    let status: RecorderStatus
}

struct RecorderProvider: TimelineProvider {
    func placeholder(in context: Context) -> RecorderEntry {
        RecorderEntry(date: Date(), status: .idle)
    }

    func getSnapshot(in context: Context, completion: @escaping (RecorderEntry) -> Void) {
        completion(RecorderEntry(date: Date(), status: RecorderStatus.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RecorderEntry>) -> Void) {
        let entry = RecorderEntry(date: Date(), status: RecorderStatus.load())
        // The app reloads this kind on every state change; the 15 minute refresh only covers a
        // missed reload while the app was suspended.
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(900))))
    }
}

struct RecorderStatusView: View {
    @Environment(\.widgetFamily) private var family
    let entry: RecorderEntry

    private var label: String {
        switch entry.status.state {
        case "recording": return "Recording"
        case "interrupted": return "Paused"
        case "uploading": return "Uploading"
        case "uploaded": return "Uploaded"
        case "failed": return "Upload failed"
        case "stopped": return "Stopped"
        default: return "Idle"
        }
    }

    private var symbol: String {
        entry.status.state == "recording" ? "mic.fill" : "mic"
    }

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: symbol)
            }
        case .accessoryInline:
            Label(label, systemImage: symbol)
        default:
            VStack(alignment: .leading) {
                Label(label, systemImage: symbol).font(.headline)
                if entry.status.durationMs > 0 {
                    Text("\(entry.status.durationMs / 1000)s")
                }
            }
        }
    }
}

@main
struct RecorderStatusWidgetBundle: WidgetBundle {
    var body: some Widget {
        RecorderStatusWidget()
    }
}

struct RecorderStatusWidget: Widget {
    static let kind = "NXRecorderStatus"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: RecorderProvider()) { entry in
            if #available(iOS 17.0, *) {
                RecorderStatusView(entry: entry).containerBackground(.clear, for: .widget)
            } else {
                RecorderStatusView(entry: entry)
            }
        }
        .configurationDisplayName("Recorder")
        .description("Shows whether a recording is in progress.")
        .supportedFamilies([.accessoryRectangular, .accessoryCircular, .accessoryInline])
    }
}
