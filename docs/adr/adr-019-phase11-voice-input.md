# ADR-019 — Phase 11 voice-input experiment (§43.2-11.5, community ask #305)

| | |
|---|---|
| **Status** | Accepted — adopted as an EXPERIMENTAL, availability-gated surface (2026-10-03) |
| **Decides** | whether voice input (dictation into the composer) ships, and how |

## Context

§43.2-11.5 mandates a voice-input experiment: "#305 ask; measure feasibility;
ADR if adopted." §50.3 frames voice-first control as a product direction, not
architecture; dictation into the existing composer is the smallest honest slice
(no new protocol surface — Phase 11 non-scope holds).

## Decision (the smallest adopted surface)

1. **Platform `SpeechRecognizer` only** — no new dependency, no third-party
   speech SDK. The `android.speech` recognition service is the user's device
   capability; the app adds no network path of its own (§27 data flows: audio
   goes to the platform recognition service under Android's own privacy model,
   never through our code).
2. **Availability-gated affordance** — the composer mic button renders ONLY
   when `SpeechRecognizer.isRecognitionAvailable(context)` is true; a device
   without a recognition service shows nothing (never a dead button). Verified
   as the ATD image's behavior: mic feature present, recognition service absent
   → no button (graceful-degradation cell of the port contract).
3. **Final-results-only dictation** — partial results are not appended to the
   draft (no mid-word flicker, no double-append risk); a completed recognition
   appends one phrase to the draft, riding the EXISTING §17.4 draft machinery
   (draft persists as before; sends stay outbox-gated).
4. **Port seam for testability** — `VoiceInputPort` (available/listening/
   start/stop) with `PlatformVoiceInput` + `NoVoiceInput`; UI tests drive a
   fake (4/4 `VoiceInputTest` green). No recognition behavior is invented in
   tests — the fake exercises only the composer contract.

## Feasibility findings (measured 2026-10-03)

| Cell | Finding |
|---|---|
| Dependency cost | zero (platform API) |
| Gating behavior on a no-service device | verified — affordance hidden (ATD API-30; `voice_interaction_service=null`, no speech packages) |
| Composer integration | verified by Robolectric suite (start → listening indicator → final-append; error surfaces honest copy) |
| **Live recognition E2E** | **NOT EXECUTED** — no GMS/reference device in this environment carries a recognition service; requires a real device with Google (or vendor) speech to measure accuracy/latency (§35.6 physical fleet) |

## Consequences / scope guard

- The feature is **experimental by construction**: it is not claimed in any
  release note as "voice control"; it is dictation into the composer.
- Latency/accuracy budgets are NOT set (nothing measured to set them against);
  a live-device leg lands with the Phase-12 canary/matrix sweep.
- Failure paths degrade honestly: recognition errors surface "Voice input
  isn't available on this device" (localized), never a silent failure.
- If Z.ai later ships an official voice path or the community asks for
  streaming dictation, revisit with a new ADR (this one stays scoped to #305's
  minimum ask).
