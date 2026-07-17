// Chessmaster — GPL-3.0-or-later
import AVFoundation
import Foundation
import Observation

/// Speaks the coach's notes during game review (on-device TTS — nothing
/// leaves the phone). On by default; the player can mute it from the
/// analysis screen or Profile → Sound.
@Observable @MainActor
final class CoachVoice: NSObject {
    private static let key = "coach.voiceEnabled"

    var enabled: Bool {
        didSet {
            UserDefaults.standard.set(enabled, forKey: Self.key)
            if !enabled { stop() }
        }
    }
    private(set) var speaking = false

    private let synthesizer = AVSpeechSynthesizer()

    override init() {
        enabled = UserDefaults.standard.object(forKey: Self.key) as? Bool ?? true
        super.init()
        synthesizer.delegate = self
    }

    func speak(_ text: String) {
        guard enabled,
              !ProcessInfo.processInfo.arguments.contains("--uitest") else { return }
        synthesizer.stopSpeaking(at: .immediate)
        // Coach voice yields to other audio and respects the mute switch.
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.duckOthers])
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.95
        utterance.pitchMultiplier = 1.02
        utterance.voice = Self.preferredVoice
        speaking = true
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        speaking = false
    }

    /// The best-quality installed English voice (users with an enhanced
    /// Siri voice downloaded get it automatically).
    private static let preferredVoice: AVSpeechSynthesisVoice? = {
        let english = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix("en") }
        return english.first { $0.quality == .premium }
            ?? english.first { $0.quality == .enhanced }
            ?? AVSpeechSynthesisVoice(language: "en-US")
    }()
}

extension CoachVoice: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.speaking = false }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                                       didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.speaking = false }
    }
}
