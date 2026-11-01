// Chessmaster — GPL-3.0-or-later
import SwiftUI

/// Asks for the player's name when sign-in didn't give us one (email
/// sign-up, Apple after the first authorization). Friends see it in
/// invites, challenges and pushes ("Evan invited you").
struct NameScreen: View {
    @Environment(DependencyContainer.self) private var container
    let source: String
    let onDone: () -> Void

    @State private var name = ""
    @State private var saving = false
    @FocusState private var focused: Bool

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Name")
                .font(.system(size: 34, weight: .bold))
                .padding(.horizontal, 28)
                .padding(.top, 60)
            TextField("Your name", text: $name)
                .font(.title3)
                .textContentType(.name)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.continue)
                .focused($focused)
                .onSubmit(save)
                .padding(18)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 24)
                .padding(.top, 28)
            Spacer()
            CapsuleCTA(label: "Continue", enabled: !trimmed.isEmpty && !saving, action: save)
        }
        .background(Color(.systemBackground).ignoresSafeArea())
        .onAppear {
            focused = true
            container.sync.track("name_prompt_shown", ["source": source])
        }
    }

    private func save() {
        guard !trimmed.isEmpty, !saving else { return }
        saving = true
        Task { @MainActor in
            await container.sync.setDisplayName(trimmed)
            container.sync.track("name_saved", ["source": source])
            saving = false
            onDone()
        }
    }
}
