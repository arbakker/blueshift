# Feature Idea: Open Spotify or Qobuz After Copying Track Info

## Summary

When the user taps the "copy now playing" button in the widget, we already copy a clean `artist - track` string to the clipboard. This feature idea explores adding a follow-up flow that helps the user quickly open Spotify or Qobuz to search for the same track.

The goal is to reduce friction for users who discover music via BluOS (radio, streams, etc.) and then want to:

- Add the track to a playlist in Spotify/Qobuz
- Explore more from the same artist
- Switch to listening in their streaming app of choice

## User Experience

### Trigger

- User taps the copy button in the widget.
- Clipboard is updated with `artist - track`.

### Possible follow-up UX options

1. **In-app prompt (best UX, more work)**
   - When the app (not just the widget) is in the foreground and detects a new clipboard value from our own copy action, show a small, dismissible prompt or bottom sheet:
     - "Open in… [Spotify] [Qobuz] [Dismiss]"
   - Selecting Spotify/Qobuz:
     - If the app is installed: open it.
     - If not installed: open Play Store listing or show a message.

2. **Notification shortcut (works even if app not open)**
   - When copying from the widget, also post a notification:
     - Title: "Track info copied"
     - Text: `artist - track`
     - Actions: `Open Spotify`, `Open Qobuz`.
   - Tapping an action attempts to launch the respective app.

3. **Settings-toggle behavior**
   - Add options in `SettingsActivity`:
     - [ ] Show notification after copying now playing
     - [ ] Include shortcuts to Spotify
     - [ ] Include shortcuts to Qobuz
   - Default: off, to keep behavior simple unless user opts in.

## Technical Considerations

### Detecting app installation

We can safely check whether a streaming app is installed using the package manager:

```kotlin
fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
```

Likely package names (to be verified on-device):

- Spotify: `com.spotify.music`
- Qobuz: `com.qobuz.music`

### Launching the apps

We should use their main launch intent, so we stay on a supported path:

```kotlin
fun launchApp(context: Context, packageName: String) {
    val pm = context.packageManager
    val launchIntent = pm.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    } else {
        // Optional: fall back to Play Store or show a toast
    }
}
```

**Important:**

- We *do not* rely on undocumented deep links or search intents like `com.spotify.music.SEARCH` or any unknown Qobuz action.
- The feature will open the app, and the user can paste the already-copied `artist - track` into the search box.

### Coupling to the existing copy behavior

We currently:

- Compute an `artist - track` string when updating `now_playing`.
- Store it in a static `lastNowPlayingForClipboard` field.
- On widget copy, read that value and put it into the clipboard.

For this feature idea, we could extend that flow:

1. **Widget -> Broadcast to app**
   - When the copy action fires, in addition to writing the clipboard, send a broadcast or start a service in the main app with the copied string.

2. **App reacts**
   - If the app is running or allowed to post notifications, show a notification with optional actions for Spotify/Qobuz.

  This decouples the widget logic (simple copy) from the richer app-side UX (notifications, prompts, etc.).

### Privacy and user expectations

- We only work with text we ourselves just wrote to the clipboard (`artist - track`), not arbitrary clipboard contents.
- We should be transparent in settings about any auto-prompt/notification behavior and let users opt out.

## Open Questions

- Should the notification/prompt appear **every** time, or only when a supported app is installed?
- Do we want separate toggles for Spotify and Qobuz, or a single "offer streaming app shortcuts" option?
- Is it acceptable to only open the app and rely on the user to paste the search query, or do we want to experiment with unofficial deep links for power users (with a clear "experimental / may break" label)?

## Minimal Viable Implementation

1. Add a settings toggle: "Show shortcuts after copying now playing".
2. When the widget copy action runs and the toggle is enabled:
   - Post a notification with the copied `artist - track`.
   - Add actions:
     - `Open Spotify` (only if installed).
     - `Open Qobuz` (only if installed).
3. Keep the search step manual: user pastes the track info into the streaming app's search.

This gives users a clear, low-friction path from the widget to their streaming app, without depending on any undocumented app-specific intents.
