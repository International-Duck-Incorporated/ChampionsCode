# Publishing a release

Releases are built and published by GitLab CI from the `main` branch. The
published artifacts live only in
[GitLab Releases](https://gitlab.com/international-duck-incorporated/ChampionsCode/-/releases);
no binaries are committed to the repository.

Release asset names are load-bearing: the in-game updater matches
`champions-mod-<version>.jar` and `SHA256SUMS.txt` by exact name (see
`docs/update-system.md`), so keep those file names stable.

## Release flow

1. Update the version in `gradle.properties`. It must keep a prerelease
   suffix (for example `-beta.1`) until the first stable release.
2. Move the changelog's `[Unreleased]` section to a dated
   `## <version> - <date>` heading and start a fresh `[Unreleased]`.
3. Commit, tag, and push from PowerShell:

```powershell
git add -A
git commit -m "Release <version>"
git tag v<version>
git push origin main v<version>
```

For the current beta this would read `0.1.0-beta.2` and `v0.1.0-beta.2`.

4. CI validates that the tag matches `gradle.properties`, verifies the
   configured Fabric 26.3 dependency versions, runs the clean build and test
   suite, checks the packaged `fabric.mod.json` and client mixin
   configuration, generates the SHA-256 manifest, and publishes the GitLab
   Release with the binary JAR, source JAR, and `SHA256SUMS.txt` attached.
   Release notes are extracted automatically from the matching changelog
   section.

Tags are immutable: if `v<version>` already points at a different commit, the
release job stops and the version must be bumped first. Pushes to `main` and
merge requests only build and test; they never publish.

## Local verification

With Java 25 active:

```powershell
.\gradlew.bat clean build
```

CI runs the same build on every push. Do not commit Minecraft account/session
data, access tokens, `.minecraft` contents, local Gradle caches, IDE settings,
or generated `build/` output. Those common paths are already covered by
`.gitignore`, which also ignores a local `release/` folder.
