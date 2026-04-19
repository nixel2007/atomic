# iOS app wrapper

Thin SwiftUI shell that hosts the shared Compose UI via a
`UIViewControllerRepresentable`. Bundle id: `dev.atomic.app`,
minimum deployment target: iOS 15.

## One-time setup on a Mac

```sh
brew install xcodegen
cd iosApp
xcodegen generate
open iosApp.xcodeproj
```

`xcodegen` reads `project.yml` and produces the Xcode project. The
generated `.xcodeproj` is gitignored so the YAML is the single
source of truth — regenerate after editing sources or settings.

The generated project has a pre-build phase that runs
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, which
builds and signs `ComposeApp.framework` for the active SDK/arch and
drops it under `composeApp/build/xcode-frameworks/` where the Xcode
link step picks it up.

## Signing

Automatic signing is enabled but `DEVELOPMENT_TEAM` is empty in
`project.yml` — set it to your Apple developer team ID before the
first archive (Xcode will surface this as a signing error until you
pick a team in the target's Signing & Capabilities tab, or edit
`project.yml` and regenerate).

## Icon

The `AppIcon.appiconset` is currently a placeholder. Drop a
1024×1024 PNG named `AppIcon.png` into
`iosApp/Assets.xcassets/AppIcon.appiconset/` and add the filename
to its `Contents.json` before submitting to the App Store.
