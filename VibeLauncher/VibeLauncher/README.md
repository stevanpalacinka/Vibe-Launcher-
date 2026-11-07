# Vibe Launcher - build without Android Studio
1. Create a free GitHub repo and upload this whole folder (keep the .github folder).
2. Open the repo's Actions tab, run "build" (it also runs on every push).
3. When it finishes, download the "VibeLauncher-apk" artifact, unzip it, then:
   adb connect <TV_IP>:5555
   adb install -r app-release.apk
4. Press Home on the remote and choose Vibe Launcher.
