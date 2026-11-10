# GIcode

GIcode is a mobile-first AI coding and Android device-assistance app for Android 10+ with a focus on ARMv7 compatibility.

## Design
- 3D-inspired cyber terminal UI with neon green accents
- Local file access through Android Storage Access Framework (SAF)
- Optional Accessibility Service for user-authorized UI automation
- Termux handoff for shell workflows (no root)
- Skill-compatible `SKILL.md` loader
- AI provider configuration via OpenAI-compatible APIs
- GitHub Actions builds a universal APK including `armeabi-v7a`

## Safety
Destructive actions require explicit confirmation. The app does not bypass Android permissions, collect credentials, or perform hidden actions.

## Build
Use the GitHub Actions workflow in `.github/workflows/android-apk.yml`.
