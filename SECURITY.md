# Security policy

Please do not put credentials, keystores, OAuth tokens, private audio files, or
vulnerability details into a public GitHub issue.

The public source tree intentionally excludes signing keys and user content.
GitHub Actions debug artifacts are not production-signed releases.

If you discover a security vulnerability, contact the maintainer privately
through their GitHub profile or use GitHub private vulnerability reporting
if it is enabled for this repository.

Optional AI model downloads connect directly to third-party model hosts and
are governed by their respective terms. MDX model downloads currently use
HTTPS and a minimum-size check; they do **not** have published pinned
SHA-256 checksums in the app. Treat model downloads as a separate trust boundary.
