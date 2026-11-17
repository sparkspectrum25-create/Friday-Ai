# Friday – GitHub se APK banane ke steps

1. Is poore folder ko ek naye GitHub repo me push karo.
2. (Wake word chahiye to)
   - Picovoice Console se AccessKey lo aur "Hey Friday" ki `.ppn` file (Android) banao.
   - `.ppn` file ko `app/src/main/assets/hey_friday.ppn` naam se rakho.
   - Repo > Settings > Secrets and variables > Actions > New secret: `PICOVOICE_ACCESS_KEY`.
   - Bina key/ppn ke bhi app build hogi, bas wake word band rahega.
3. Repo > Actions > "Build APK" > Run workflow.
4. Run khatam hone par neeche Artifacts me `friday-debug-apk` download karo, zip kholo, `app-debug.apk` install karo.

Phone me pehli baar: Notification access ON, battery "Unrestricted", aur wake word ke liye "Display over other apps" allow karo.
Purani APK ki signature alag thi, to pehle purani Friday uninstall karni padegi.
