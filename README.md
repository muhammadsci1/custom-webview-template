# Custom WebView Template

A production-ready Android WebView wrapper with full hardware access, offline support,
and zero restrictions. Built for bundling web applications as native Android APKs.

## Features

- **Dual-Mode Architecture** — Landing page → Main app with configurable delay
- **Offline-First** — Loads local `index.html` if present, falls back to remote URL
- **First-Launch Offline Detection** — Beautiful error page with instructions
- **Full CORS Bypass** — `allowUniversalAccessFromFileURLs`, `allowFileAccessFromFileURLs`
- **Auto-Grant Hardware** — Camera, Microphone, Geolocation granted automatically
- **File Chooser** — Native Android file picker for uploads
- **Download Manager** — Standard file downloading support
- **Back Button History** — Proper WebView back navigation
- **CI/CD Ready** — GitHub Actions build & artifact upload included

## Quick Start

1. **Clone or download** this repository
2. **Update `app/src/main/assets/config.json`** with your `launch_url`
3. **Add your web files** to `app/src/main/assets/www/` (optional):
   - `index.html` — Main app (loaded offline if present)
   - `landing.html` — Splash/landing page (shown for 3 seconds if present)
4. **Replace icons** in `app/src/main/res/mipmap-*/` with your own
5. **Build**: `./gradlew assembleDebug` or push to GitHub for CI build

## Directory Structure

```
custom-webview-template/
├── .github/workflows/build.yml     # GitHub Actions CI pipeline
├── app/
│   ├── build.gradle                # App-level Gradle config (SDK 34)
│   ├── proguard-rules.pro          # ProGuard rules for release builds
│   └── src/main/
│       ├── AndroidManifest.xml     # All permissions & activity declarations
│       ├── assets/
│       │   ├── config.json         # Remote URL & app config
│       │   ├── error.html          # First-launch offline error page
│       │   └── www/                # Your web application files
│       ├── java/com/custom/browser/
│       │   └── MainActivity.java   # Core WebView logic
│       └── res/
│           ├── layout/             # activity_main.xml
│           ├── values/             # strings, colors, styles
│           ├── xml/                # network_security_config, file_paths
│           └── mipmap-*/           # Launcher icons (all densities)
├── build.gradle                    # Root Gradle config
└── settings.gradle                 # Project settings
```

## Configuration

Edit `app/src/main/assets/config.json`:

```json
{
  "launch_url": "https://your-app.com",
  "app_name": "Your App Name",
  "landing_enabled": true,
  "landing_delay_ms": 3000
}
```

## Icon Generation

The project includes a default placeholder icon. To generate proper icons:

1. Place a 512×512 PNG in the project root as `icon_512.png`
2. Run: `python3 generate_icons.py`

The script handles the fallback chain:
1. Custom Android/Apple touch icons (if provided)
2. Website favicon extraction
3. Auto-generate a colored square with the first letter of the app title

## Building

### Local Build

```bash
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

Push to `main` or `master`, or trigger manually via the Actions tab.
The built APK will be available as an artifact.

## Permissions

The app requests and auto-grants:

| Permission | Purpose |
|---|---|
| `INTERNET` | Network access |
| `CAMERA` | Camera access for web apps |
| `RECORD_AUDIO` | Microphone access |
| `ACCESS_FINE_LOCATION` | Geolocation |
| `READ/WRITE_EXTERNAL_STORAGE` | File uploads & downloads |

## License

MIT — Use freely in your projects.