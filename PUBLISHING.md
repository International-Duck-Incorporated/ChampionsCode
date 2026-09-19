# Publishing to GitHub from PowerShell

The source archive intentionally does not contain a `.git` directory or stored
credentials. After extracting it, open PowerShell in the extracted project root
(the directory containing `gradlew.bat`) and run:

```powershell
git init
git add .
git commit -m "Champions Mod 0.1.0-alpha.11"
git remote add origin https://github.com/International-Duck-Incorporated/ChampionsCode.git
git branch -M main
git push -u origin main
```

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
