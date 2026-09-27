# Nave Sleep Widget

Android home-screen widget for Nave's sleep: awake/asleep, time since (live), next nap window, today's naps.

- Data: read-only status feed (Supabase `sleep` function), keyed by a private per-parent link.
- Logging stays in WhatsApp (the family bot).
- Build: every push to `main` builds `nave-sleep.apk` in GitHub Actions and publishes it as the latest Release.

## Install
1. On the phone, open the latest Release and download `nave-sleep.apk` (allow installs from the browser once).
2. Open the app, paste your personal sleep-page link, tap "שמור וחבר".
3. Long-press the home screen → Widgets → השינה של נווה.
4. Samsung: App settings → Battery → Unrestricted, so the widget refreshes in the background.

The signing key in `keystore/` is only for this private family app, so updates install over the old version.
