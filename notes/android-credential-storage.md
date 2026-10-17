# Android credential storage

`faceclaw_settings` remains the cross-isolate SharedPreferences store. Only the
credential-bearing keys in `FaceclawSecretKeys` are encrypted. The Android
implementation uses the platform Android Keystore with AES-256-GCM (supported
from API 23; this app supports API 24+), a distinct generated IV per write, and
the preference name as authenticated associated data. Ciphertext uses a
versioned internal preference name. Ordinary display and behavior settings
remain in the original preference entries.

On first Android settings initialization, old plaintext credential entries are
migrated. Each encrypted write is committed, read back, and authenticated
before its plaintext counterpart is removed. If the process stops between
those steps, the next initialization uses the ciphertext and retries cleanup.
Keystore failure, key loss, invalidation, or ciphertext corruption raises an
error. The app does not read a plaintext fallback or generate a replacement key
while encrypted entries exist. Recovery requires deliberate removal of the
unreadable encrypted entries and re-entry of credentials; do not restore a
previous plaintext settings file over them.

Android automatic cloud backup and device transfer are disabled in the
manifest and excluded by explicit legacy and Android 12+ rules. The adb config
port exports ordinary settings only and rejects imported credential and
internal encryption entries. `pull_config.sh` and `push_config.sh` use that
port on debug and release builds. Old full settings exports, manual `run-as`
copies, and any pre-existing `.bak` files should be treated as sensitive and
removed securely by their owner after they are no longer needed. Transferring
ordinary settings to a new phone requires re-entering credentials there.

iOS settings storage remains `NSUserDefaults` through
`app/native/settings-store.ios.ts`; this Android change does not encrypt iOS
credentials or alter iOS backup and config-port behavior. iOS Keychain storage
and its migration need a separate review. EvenHub app-local storage uses
NativeScript `ApplicationSettings` rather than `faceclaw_settings`; arbitrary
third-party app values there are outside this credential inventory.
