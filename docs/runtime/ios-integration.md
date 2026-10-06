# iOS Background Download Integration

This document outlines the setup required to run background transfers on iOS using `downloader-runtime`.

---

## 1. Capabilities & Plist Configuration

To ensure iOS and Apple's root daemon (`nsurlsessiond`) can wake or relaunch your application when a background download finishes, configure the required background modes.

In your iOS app's **`Info.plist`** (or Target $\rightarrow$ **Signing & Capabilities** $\rightarrow$ **+ Capability** $\rightarrow$ **Background Modes**):

```xml
<key>UIBackgroundModes</key>
<array>
    <string>fetch</string>
    <string>processing</string>
</array>