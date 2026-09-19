# Open-source integration notes

This branch uses open-source projects as architectural references; it does not vendor an entire third-party assistant.

## References

- Sanna: https://github.com/sannabotdev/sannabotapp — MIT. Useful reference for the wake-word → STT → LLM → TTS agent loop, multi-step execution, Accessibility automation, and skill-oriented architecture.
- Android Agent: https://github.com/adev0x/android-agent — MIT. Useful reference for the perceive → think → act loop using screenshots + Accessibility actions and confirmation gates.
- Brownie: https://github.com/natanloterio/Brownie — MIT. Useful reference for an agent-first Android architecture with tools, skills, memory, scheduling and Accessibility automation.

## Integration rule

Malaram Personal Assistant remains a native Kotlin Android application. Existing Hindi speech recognition, Sherpa wake-word detection, Hindi female TTS, Accessibility service, safety confirmation, and Gemini fallback are retained unless a replacement is demonstrably more reliable.

We prefer:
1. small, reversible changes;
2. permissive licenses (MIT/Apache-2.0) for reusable code;
3. adapting architecture rather than copying an entire application;
4. keeping sensitive actions behind explicit confirmation;
5. verifying every change with GitHub Actions before merging to main.

## Current branch work

- Local planner now accepts more Hindi/English aliases and simple multi-step commands joined with "और", "फिर", or "और फिर".
- VoiceInteractionService starts the microphone worker as a foreground service on Android O+ and stops it when the assistant service shuts down.
