# WeatherMCPApp

Android app project built with Gradle and Kotlin.

## Demo Repository

This repository is the team demo/integration version of the project.

- Original backend repository: https://github.com/ethandamark/CMPS-490-BACKEND-REPO
- Original frontend repository: <add-frontend-repo-url-here>

Use this repo to review demo progress and integration changes without modifying the original source repos.

## Build

From the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Troubleshooting

### Windows + OneDrive `AccessDeniedException` during build

If you see errors like:

- `Task :app:dexBuilderDebug FAILED`
- `java.nio.file.AccessDeniedException` under `app/build/intermediates/...`

This project is already configured to avoid that lock issue by:

- Writing Gradle module build outputs outside OneDrive (to `%LOCALAPPDATA%/WeatherMCPApp/build`, with temp-dir fallback)
- Disabling Gradle VFS watch (`org.gradle.vfs.watch=false`)

If lock errors still happen:

1. Stop daemons: `./gradlew.bat --stop`
2. Rebuild: `./gradlew.bat :app:clean :app:assembleDebug --stacktrace`
3. Pause OneDrive sync temporarily while building (if needed)
