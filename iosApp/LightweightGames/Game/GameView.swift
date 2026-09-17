import UIKit
import GamesCore

/// `CADisplayLink` retains its target strongly. Using `GameView` itself as
/// the target would create a permanent retain cycle (view -> link ->
/// view), leaking every `GameView` (and the `GameSession`/`Scene` it owns)
/// for the lifetime of the app. This proxy holds a *weak* reference
/// instead, so a dismissed `GameViewController` and its `GameView` can be
/// deallocated normally; `GameView.deinit` then explicitly invalidates the
/// link to remove it from the run loop.
private final class DisplayLinkProxy {
    weak var target: GameView?

    init(target: GameView) {
        self.target = target
    }

    @objc func step(_ link: CADisplayLink) {
        target?.handleDisplayLinkStep(link)
    }
}

/// Renders one `GameSession` and forwards touches/frames to it. Owns no
/// game logic itself: every pointer event and every tick goes straight to
/// the Kotlin core (`lg.core.session.GameSession`), which is the sole
/// authority on game state, overlays, and what ends up in the `Scene`
/// (PLAN.md §2).
final class GameView: UIView {
    private let session: GameSession
    private let renderer = CoreGraphicsRenderer()
    private let scene = Scene(capacity: 1024)
    private let haptics = Haptics()
    private let soundSink: SoundSink

    private var cgColors: [CGColor] = ScenePalette.cgColors(for: .current)
    private var displayLinkStorage: CADisplayLink?
    private lazy var displayLinkProxy = DisplayLinkProxy(target: self)
    private var lastFrameTimestamp: CFTimeInterval?

    // Stable pointer ids: the array index doubles as the id passed to
    // `session.onPointer`. A slot is `nil` when free. Fixed-size and
    // preallocated so multi-touch never allocates per event, matching the
    // core's own zero-allocation-per-frame rule (PLAN.md §7).
    private static let maxPointers = 10
    private var trackedTouches: [UITouch?] = Array(repeating: nil, count: GameView.maxPointers)

    init(session: GameSession, soundSink: SoundSink = NoopSoundSink()) {
        self.session = session
        self.soundSink = soundSink
        super.init(frame: .zero)
        isMultipleTouchEnabled = true
        isOpaque = true
        contentScaleFactor = UIScreen.main.scale
        backgroundColor = ScenePalette.colors[PaletteIndex.bg]
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    deinit {
        displayLinkStorage?.invalidate()
    }

    // MARK: - Frame loop

    private var displayLink: CADisplayLink {
        if let link = displayLinkStorage { return link }
        let link = CADisplayLink(target: displayLinkProxy, selector: #selector(DisplayLinkProxy.step(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        link.isPaused = true
        link.add(to: .main, forMode: .common)
        displayLinkStorage = link
        return link
    }

    /// Re-evaluated after every input event and every `update`, per
    /// PLAN.md §5: the display link only runs while `needsFrameLoop` is true.
    private func syncFrameLoop() {
        let needed = session.needsFrameLoop
        displayLink.isPaused = !needed
        if !needed {
            lastFrameTimestamp = nil
        }
    }

    fileprivate func handleDisplayLinkStep(_ link: CADisplayLink) {
        let now = link.timestamp
        let last = lastFrameTimestamp ?? now
        lastFrameTimestamp = now
        let elapsedMs = Int32(((now - last) * 1000).rounded())
        let dtMs = max(0, min(elapsedMs, 50)) // GameSession also clamps; this just avoids a huge first frame.
        let redraw = session.update(dtMs: dtMs)
        syncFrameLoop()
        if redraw {
            setNeedsDisplay()
        }
    }

    // MARK: - Lifecycle / layout

    override func layoutSubviews() {
        super.layoutSubviews()
        session.resize(width: Float(bounds.width), height: Float(bounds.height))
        setNeedsDisplay()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            cgColors = ScenePalette.cgColors(for: traitCollection)
            backgroundColor = ScenePalette.colors[PaletteIndex.bg]
            syncFrameLoop()
            setNeedsDisplay()
        } else {
            displayLinkStorage?.isPaused = true
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        cgColors = ScenePalette.cgColors(for: traitCollection)
        backgroundColor = ScenePalette.colors[PaletteIndex.bg]
        setNeedsDisplay()
    }

    // MARK: - Drawing

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        session.render(scene: scene)
        renderer.draw(scene, in: context, colors: cgColors)
        if scene.haptic {
            haptics.pulse()
        }
        if scene.soundMask != 0 {
            soundSink.play(mask: scene.soundMask)
        }
    }

    // MARK: - Touch input

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let slot = allocateSlot(for: touch) else { continue }
            let point = touch.location(in: self)
            session.onPointer(id: Int32(slot), phase: .down, x: Float(point.x), y: Float(point.y))
        }
        afterInput()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let slot = slot(for: touch) else { continue }
            let point = touch.location(in: self)
            session.onPointer(id: Int32(slot), phase: .move, x: Float(point.x), y: Float(point.y))
        }
        afterInput()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let slot = slot(for: touch) else { continue }
            let point = touch.location(in: self)
            session.onPointer(id: Int32(slot), phase: .up, x: Float(point.x), y: Float(point.y))
            freeSlot(slot)
        }
        afterInput()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let slot = slot(for: touch) else { continue }
            let point = touch.location(in: self)
            session.onPointer(id: Int32(slot), phase: .cancel, x: Float(point.x), y: Float(point.y))
            freeSlot(slot)
        }
        afterInput()
    }

    private func afterInput() {
        syncFrameLoop()
        setNeedsDisplay()
    }

    private func allocateSlot(for touch: UITouch) -> Int? {
        if let existing = slot(for: touch) { return existing }
        for i in 0..<GameView.maxPointers where trackedTouches[i] == nil {
            trackedTouches[i] = touch
            return i
        }
        return nil // More than maxPointers simultaneous touches: drop silently.
    }

    private func slot(for touch: UITouch) -> Int? {
        trackedTouches.firstIndex { $0 === touch }
    }

    private func freeSlot(_ index: Int) {
        trackedTouches[index] = nil
    }
}
