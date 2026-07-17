// AudioKitChess — Chessmaster
// GPL-3.0-or-later
//
// All bundled sounds are original synthesized audio (no third-party assets).

import AVFoundation
import Observation

public enum SoundEffect: String, CaseIterable, Sendable {
    case move
    case capture
    case castle
    case check
    case promote
    case gameStart = "game-start"
    case gameEnd = "game-end"
    case lowTime = "low-time"
}

/// Low-latency sound playback: every effect is preloaded into a PCM buffer
/// and triggered on a small pool of player nodes (an `AVAudioPlayer` per
/// play has audible lag for rapid move sequences). Background music loops
/// on its own node with independent volume.
@Observable @MainActor
public final class ChessAudioPlayer {
    public var soundsEnabled = true {
        didSet { UserDefaults.standard.set(soundsEnabled, forKey: "audio.sounds") }
    }
    public var musicEnabled = false {
        didSet {
            UserDefaults.standard.set(musicEnabled, forKey: "audio.music")
            musicEnabled ? startMusic() : stopMusic()
        }
    }

    private let engine = AVAudioEngine()
    private var effectBuffers: [SoundEffect: AVAudioPCMBuffer] = [:]
    private var effectNodes: [AVAudioPlayerNode] = []
    private var nextNode = 0
    private let musicNode = AVAudioPlayerNode()
    private var musicBuffer: AVAudioPCMBuffer?
    private var prepared = false

    public init() {
        soundsEnabled = UserDefaults.standard.object(forKey: "audio.sounds") as? Bool ?? true
        musicEnabled = UserDefaults.standard.object(forKey: "audio.music") as? Bool ?? false
    }

    public func play(_ effect: SoundEffect) {
        guard soundsEnabled else { return }
        prepareIfNeeded()
        guard let buffer = effectBuffers[effect], !effectNodes.isEmpty else { return }
        let node = effectNodes[nextNode]
        nextNode = (nextNode + 1) % effectNodes.count
        node.scheduleBuffer(buffer, at: nil)
        node.play()
    }

    public func startMusicIfEnabled() {
        if musicEnabled { startMusic() }
    }

    // MARK: - Setup

    private func prepareIfNeeded() {
        guard !prepared else { return }
        prepared = true

        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif

        for effect in SoundEffect.allCases {
            guard let url = Bundle.module.url(forResource: effect.rawValue, withExtension: "wav"),
                  let buffer = Self.loadBuffer(url: url) else { continue }
            effectBuffers[effect] = buffer
        }

        let format = effectBuffers.values.first?.format
        for _ in 0..<4 {
            let node = AVAudioPlayerNode()
            engine.attach(node)
            engine.connect(node, to: engine.mainMixerNode, format: format)
            effectNodes.append(node)
        }

        engine.attach(musicNode)
        if let url = Bundle.module.url(forResource: "music-loop", withExtension: "m4a"),
           let buffer = Self.loadBuffer(url: url) {
            musicBuffer = buffer
            engine.connect(musicNode, to: engine.mainMixerNode, format: buffer.format)
            musicNode.volume = 0.5
        }

        try? engine.start()
    }

    private func startMusic() {
        prepareIfNeeded()
        guard let musicBuffer, !musicNode.isPlaying else { return }
        musicNode.scheduleBuffer(musicBuffer, at: nil, options: .loops)
        musicNode.play()
    }

    private func stopMusic() {
        musicNode.stop()
    }

    private static func loadBuffer(url: URL) -> AVAudioPCMBuffer? {
        guard let file = try? AVAudioFile(forReading: url),
              let buffer = AVAudioPCMBuffer(
                pcmFormat: file.processingFormat,
                frameCapacity: AVAudioFrameCount(file.length))
        else { return nil }
        try? file.read(into: buffer)
        return buffer
    }
}
