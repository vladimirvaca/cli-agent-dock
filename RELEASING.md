# Releasing CLI Agent Dock

This document describes the versioning strategy and the release procedure for the plugin.
Releases are driven by **git tags**: pushing a `vX.Y.Z` tag runs the
[Release workflow](.github/workflows/release.yml), which builds and signs the plugin,
publishes it to JetBrains Marketplace, and creates a GitHub Release with the plugin ZIP
attached and release notes containing **only that version's changelog section**.

## Versioning strategy

The plugin uses [Semantic Versioning](https://semver.org) in the `0.x` range:

- **MINOR** (`0.1.0` → `0.2.0`) — new functionality: new agents, new tool-window
  features, new settings.
- **PATCH** (`0.1.0` → `0.1.1`) — bug fixes and internal improvements with no new
  user-facing behavior.
- **MAJOR** → `1.0.0` when the plugin is considered stable (feature set and settings
  format are settled).

Rules:

- The version lives in **one place only**: the `version` property in
  [`gradle.properties`](gradle.properties). Everything else (plugin.xml patching,
  changelog sections, Marketplace channel) derives from it.
- Release tags are the version prefixed with `v`: version `0.1.0` → tag `v0.1.0`.
  The Release workflow fails fast if the tag and `gradle.properties` disagree.
- Pre-releases use a hyphen suffix, e.g. `0.2.0-beta.1`. They are automatically
  published to the matching custom Marketplace channel (`beta`) instead of the default
  channel, so only users who opt into that channel receive them.

## One-time setup

### 1. Plugin signing certificate

Generate a private key and a self-signed certificate
(see [JetBrains plugin signing docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)):

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

### 2. Marketplace token

Create a Personal Access Token in your
[JetBrains Marketplace profile](https://plugins.jetbrains.com/author/me/tokens).

### 3. GitHub repository secrets

Add these under **Settings → Secrets and variables → Actions**. The certificate/key
secrets contain the **PEM file contents** (including the `BEGIN`/`END` lines, newlines
preserved), not file paths:

| Secret | Value |
| --- | --- |
| `PUBLISH_TOKEN` | Marketplace personal access token |
| `CERTIFICATE_CHAIN` | Contents of `chain.crt` |
| `PRIVATE_KEY` | Contents of `private.pem` |
| `PRIVATE_KEY_PASSWORD` | Password used when generating the key |

### 4. First Marketplace upload (manual)

JetBrains requires the **first version of a plugin to be uploaded manually**:
build it with `./gradlew buildPlugin` and upload
`build/distributions/cli-agent-dock-<version>.zip` at
[plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/add). `publishPlugin`
(used by the Release workflow) can only update an existing, approved listing.

Once the plugin is approved, replace the `MARKETPLACE_ID` placeholders in
[`README.md`](README.md) with the numeric id from the plugin's Marketplace URL.

## Release procedure

1. Make sure the `[Unreleased]` section in [`CHANGELOG.md`](CHANGELOG.md) accurately
   describes the changes being shipped (it becomes both the GitHub release notes and
   the Marketplace "What's New").
2. Bump `version` in `gradle.properties`, e.g. `version = 0.1.0`.
3. Move `[Unreleased]` into a dated `[0.1.0]` section:

   ```bash
   ./gradlew patchChangelog
   ```

   (The first run also appends a comparison-links footer to `CHANGELOG.md` — expected.)
4. Review `CHANGELOG.md`, then commit and push:

   ```bash
   git add gradle.properties CHANGELOG.md
   git commit -m "chore(release): v0.1.0"
   git push
   ```

   Wait for the **Build** workflow to go green.
5. Tag and push the tag — this triggers the **Release** workflow:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

6. Watch **Actions → Release**, then verify the GitHub Release and the
   Marketplace listing (Marketplace review of an update can take a little while).

## Troubleshooting

- **"Tag does not match gradle.properties version"** — you tagged the wrong commit or
  forgot to bump the version. Delete the tag, fix, re-tag:

  ```bash
  git push origin :refs/tags/v0.1.0
  git tag -d v0.1.0
  # fix gradle.properties / tag the right commit, then re-tag and push
  ```

- **"Extract release notes" fails** — `CHANGELOG.md` has no `[X.Y.Z]` section, meaning
  `./gradlew patchChangelog` was not run before tagging. Run it, commit, delete and
  re-push the tag (see above).
- **Marketplace publish succeeded but the GitHub Release step failed** — do not re-run
  the whole workflow (Marketplace rejects an already-uploaded version). Create the
  release manually:

  ```bash
  ./gradlew getChangelog --project-version=0.1.0 --no-header --no-summary -q --output-file=notes.md
  gh release create v0.1.0 --title v0.1.0 --notes-file notes.md build/distributions/*.zip
  ```

- **"Version already exists" from Marketplace on a re-run** — that version was already
  published; bump to a new PATCH version and release again rather than force-replacing.
