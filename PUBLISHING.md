# Publishing a release

Releases are built and published by GitLab CI from the `main` branch. The
published artifacts live only in
[GitLab Releases](https://gitlab.com/international-duck-incorporated/ChampionsCode/-/releases);
no binaries are committed to the repository.

The public-facing page for the project is
<https://idi-official.pages.dev/projects/championscode>, which links to those
same release files. It is a documentation and download entry point only: it
does not host the JARs, the `SHA256SUMS.txt`, or the update feed, so a release
is not published until the GitLab CI release job has attached its assets.

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

For example, version `0.2.0-beta.18` uses tag `v0.2.0-beta.18`. Always check
`gradle.properties` for the version being released.

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

## Publishing by hand

CI is the normal path, but it needs compute minutes and a runner. When neither is
available -- a free plan with zero minutes fails the pipeline within a second and
publishes nothing -- the same two steps can be done against the API with a token
that can write the package registry and create releases. This is what every
release up to 0.2.0-beta.17 used.

Upload with `PUT` (`--upload-file`), not a multipart `POST`. The multipart form
still returns `{"error":"404 Not Found"}`, which reads like a missing project or a
bad token and is neither:

```bash
for f in champions-mod-<version>.jar champions-mod-<version>-sources.jar SHA256SUMS.txt; do
  curl --fail-with-body --header "PRIVATE-TOKEN: ${TOKEN}" \
    --upload-file "build/libs/${f}" \
    "https://gitlab.com/api/v4/projects/86855171/packages/generic/champions-mod/<version>/${f}"
done
```

Then create the release with those three URLs as assets (`POST /projects/:id/releases`,
`link_type: package` for the JARs, `other` for the checksum file), and finish by
downloading the published JAR again and comparing its `sha256sum` with the local
build. If they differ, the wrong file was uploaded.

Push tags with `-o ci.skip` while CI has no minutes; otherwise the tag collects a
failed pipeline that published nothing and cannot be deleted without Pipeline
permissions.

## Local verification

With Java 25 active:

```powershell
.\gradlew.bat clean build
```

CI runs the same build on every push. Do not commit Minecraft account/session
data, access tokens, `.minecraft` contents, local Gradle caches, IDE settings,
or generated `build/` output. Those common paths are already covered by
`.gitignore`, which also ignores a local `release/` folder.
