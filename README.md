# Nave Sleep Widget

Android home-screen widget for Nave's sleep: big נרדם / התעורר buttons, a large live clock, colour that shifts with the wake window or nap length, tap the time to correct it. In the evening (19:00-07:00) it asks night or nap.

- Data: Supabase `sleep` function, keyed by a private per-parent link (read + sleep logging, approved 2026-10-22).
- WhatsApp logging still works as a silent backup (the bot reacts ✓).
- Build: every push to `main` builds `nave-sleep.apk` in GitHub Actions and publishes it as the latest Release.

## Install
1. On the phone, open the latest Release and download `nave-sleep.apk` (allow installs from the browser once).
2. Open the app, paste your personal sleep-page link, tap "שמור וחבר".
3. Long-press the home screen → Widgets → השינה של נווה.
4. Samsung: App settings → Battery → Unrestricted, so the widget refreshes in the background.

The signing key in `keystore/` is only for this private family app, so updates install over the old version.
