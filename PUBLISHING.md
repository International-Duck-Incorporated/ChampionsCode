# Publishing to GitHub from PowerShell

The source archive intentionally does not contain a `.git` directory or stored
credentials. After extracting it, open PowerShell in the extracted project root
(the directory containing `gradlew.bat`) and run:

```powershell
git init
git add .
git commit -m "ChampionsCode 0.1.0-alpha.16"
git remote add origin https://github.com/International-Duck-Incorporated/ChampionsCode.git
git branch -M main
git push -u origin main
```

A successful push to `main` now creates `v<version>` automatically and publishes
the build as a GitHub prerelease. The release contains the binary JAR, source JAR,
and SHA-256 manifest. The version in `gradle.properties` must include a
prerelease suffix such as `-alpha.16`. Reusing a tag for a different commit is
rejected, so bump the version before each new prerelease push.

Pull requests and manually dispatched builds never publish a release.

If Git asks for your identity before the commit, configure it first:

```powershell
git config --global user.name "YOUR NAME"
git config --global user.email "YOUR_GITHUB_EMAIL"
```

If `origin` already exists, replace the `git remote add` command with:

```powershell
git remote set-url origin https://github.com/International-Duck-Incorporated/ChampionsCode.git
```

## Local verification

With Java 25 active:

```powershell
.\gradlew.bat clean build
```

The GitHub Actions workflow performs the same build for pushes and pull
requests. Do not commit Minecraft account/session data, access tokens,
`.minecraft` contents, local Gradle caches, IDE settings, or generated `build/`
output. Those common paths are already covered by `.gitignore`.
