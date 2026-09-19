# Malaram Personal Assistant

माला राम जी के लिए Android personal assistant.

## अभी क्या है
- Hindi voice input (hi-IN)
- **Offline neural Hindi female voice** using Sherpa-ONNX + Piper voice model
- Voice: `vits-piper-hi_IN-priyamvada-medium`
- नाम से Hindi greeting
- YouTube / WhatsApp / Chrome / Google / Settings खोलना
- Android Accessibility Service foundation
- पहली बार voice model डाउनलोड; उसके बाद TTS offline चलता है
- GitHub Actions से automatic debug APK build

## महिला आवाज कैसे काम करती है

ऐप official Sherpa-ONNX Android runtime का उपयोग करता है। Hindi female model पहली बार GitHub release से ऐप के private storage में डाउनलोड और extract होता है। उसके बाद generated speech फोन पर ही बनती है; किसी cloud TTS API key की जरूरत नहीं होती।

Official model:
`vits-piper-hi_IN-priyamvada-medium`

Model में 1 speaker और 22050 Hz sample rate है। Sherpa-ONNX इसकी Android arm64-v8a build भी उपलब्ध कराता है।

## अगले चरण
1. Offline Hindi speech-to-text (Sherpa-ONNX ASR)
2. Accessibility से स्क्रीन पढ़ना
3. Tap / long-press / swipe / back / type
4. Multi-step voice tasks
5. Confirmation layer for sensitive actions
6. Local/open-source AI planner
7. Command history
8. Release signing

## License note
Sherpa-ONNX runtime और voice model की अलग-अलग license terms लागू हो सकती हैं। Commercial redistribution/Play Store release से पहले उन terms को verify करना जरूरी है।

## APK
हर push पर GitHub Actions debug APK बनाकर artifact में रखेगा।
