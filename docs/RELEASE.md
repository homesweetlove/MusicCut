# Signed APK release checklist

The former private app used a committed debug signing key. This public repository
**does not include it**. Do not reuse or upload that old key.

1. Generate a **new private** Android signing keystore on a trusted computer,
   and keep an encrypted offline backup. Never commit it or its passwords.
2. Base64-encode the keystore and add four repository Actions secrets:
   - `MUSICCUT_RELEASE_KEYSTORE_B64`: base64 of the keystore bytes
   - `MUSICCUT_RELEASE_STORE_PASSWORD`: keystore password
   - `MUSICCUT_RELEASE_KEY_ALIAS`: signing key alias
   - `MUSICCUT_RELEASE_KEY_PASSWORD`: key password
3. Configure an Android OAuth client in Google Cloud Console with package
   `com.musiccut.app` and the SHA-1 fingerprint of the **new** signing certificate
   if Google Drive sign-in should work in your distributed APK.
4. Run the **Signed release APK** GitHub Actions workflow manually. Provide a
   version (for example `1.0.0`). It runs the core tests, builds a release-signed
   APK, computes SHA-256, and publishes a GitHub Release only on success.
5. Install on a real Android 8+ device and check core editing, exports, model
   downloads, recording, and Drive access. Check the checksum against the Release.

**Update compatibility:** APKs signed with the old debug key cannot be updated
in place by new signed builds, even when the application ID is unchanged.
Back up any important projects and uninstall the old APK before installing
a differently signed one.

The regular `Android CI` workflow uploads **debug artifacts** for testing only.
A release may only be produced with the above private signing credentials.

For contributors, make PRs against `main`; never attach a keystore to an issue,
PR, or build artifact.
