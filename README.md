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

## Open in Android Studio (Demo Test)

Open your local cloned repository root as the project root:

- Example folder name after cloning: `WeatherMCPApp`

Use the folder that contains `settings.gradle.kts`.

Important:

- Open the root folder above (the one with `settings.gradle.kts`), not the `app` subfolder.

Run the demo:

1. Open Android Studio and choose **Open**.
2. Select your local clone root (the folder containing `settings.gradle.kts`).
3. Wait for Gradle sync to finish.
4. Choose an emulator/device.
5. Click **Run** on the `app` configuration.

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
