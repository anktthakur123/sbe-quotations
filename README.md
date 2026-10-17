# SBE Quotations — Android application project

Android 11 (API 30) and newer. `minSdk = 30`, `targetSdk = 36`.

## Implemented in the project

- Customer Master: save, load, edit and delete customer records.
- Customer name is optional; the quotation can be saved without it.
- Fixed owner details for SIDH BABA ENTERPRISES.
- Paste parser supports an item code before or after its description; fields remain editable.
- Serial number is automatic, quantity is always `01nos`, and price is manually entered.
- PDF price format uses rupees and two decimal places, e.g. `₹ 2,600.00`.
- Quotation number, quotation date and 90-day validity date.
- PDF saved as `<Quotation Number>.pdf` in `/storage/emulated/0/Quotitions Form`.
- Direct PDF share through Android's share sheet.
- More than 15 items continue on later pages; the quotation number is repeated and terms/signature appear on the final page.
- Editable History stores compact quotation data in app storage, not PDF copies. Existing quotes can be edited and exported again with the same number.
- Compact JSON backup/export and JSON import.
- GitHub Releases OTA check. APK installation always needs Android's confirmation; silent installation is not supported.

The PDF uses a high-resolution background rasterized from the supplied filled quotation PDF, preserving the original page layout, colors, fixed wording, borders, terms, signature and footer. Sample customer, date, quotation number, subject and item values have been blanked out of the bundled background asset; the app fills those fields at runtime. Variable text uses Android's condensed sans-serif font, so a small font-rendering difference from the embedded Calibri in the source PDF may remain. The rendered page is letter-sized. The subject is generated as `QUOTATION FOR SPL GAUGE - <Specific Gauge Name>`.

## Important initial values

The attached example PDF uses quote number `SB326`, so the numbering screen defaults to prefix `SB` and next serial `327`. **Before the first real quotation, verify/change the next serial number in Settings** so it matches your latest business records.

The folder name intentionally matches the requested spelling: `Quotitions Form`.

## Build and OTA release through GitHub

**APK status:** this package contains the Android project source, not a prebuilt APK. The APK is produced by the included GitHub Actions workflow after you upload the project and configure the release-signing secrets below. This environment does not have the Android SDK/Gradle installation needed to compile and device-test an APK here.

The repository must be **public** for the app's unauthenticated updater to check GitHub Releases and download the release APK. Customer details and quotation history are not uploaded to GitHub; only source and released APKs are there.

### 1. Create the signing key on your PC

Install a Java JDK (Android Studio also installs one), open PowerShell or a terminal, and run:

```bash
keytool -genkeypair -v -keystore sbe-release.keystore -alias sbequotations -keyalg RSA -keysize 2048 -validity 10000
```

Choose a strong keystore password and a key password, and save them safely. Use alias `sbequotations` as shown. Do **not** upload or commit `sbe-release.keystore` to the repository. Losing this key prevents in-place OTA updates of an already installed app.

### 2. Configure GitHub Actions secrets

Create a public GitHub repository named `sbe-quotations`, then open **Settings → Secrets and variables → Actions → New repository secret**. Add:

- `SBE_KEYSTORE_BASE64` — Base64-encoded contents of `sbe-release.keystore`.
- `SBE_KEYSTORE_PASSWORD` — the keystore password.
- `SBE_KEY_ALIAS` — `sbequotations`.
- `SBE_KEY_PASSWORD` — the key password.

On Windows PowerShell, run this from the directory containing the keystore, then paste the output into the `SBE_KEYSTORE_BASE64` secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\sbe-release.keystore")) | Set-Clipboard
```

Do not put passwords, keystore contents, or the keystore file in source files or commits. The workflow temporarily reconstructs the key only inside the GitHub Actions runner for a version-tag build.

### 3. Upload source and create the first release

Extract the project ZIP, then use Git Bash in the extracted `SBE_Quotations_Android` folder (the folder containing `settings.gradle`):

```bash
git init -b main
git add .
git commit -m "Initial SBE Quotations Android app"
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/sbe-quotations.git
git push -u origin main
```

Replace `YOUR_GITHUB_USERNAME` with the actual GitHub owner. A push to `main` creates a **debug/test APK** artifact only; do not install that as the production OTA version.

After the four repository secrets are set, create and push the first release tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Open the repository's **Actions** tab and wait for the tag build to succeed. Then download `SBE-Quotations.apk` from the `SBE Quotations v1.0.0` GitHub Release and install it on the phone. Approve the browser/file manager's install permission if Android asks.

### 4. Publish an update

Commit your source changes, push them to `main`, then create a new, never-reused version tag:

```bash
git add .
git commit -m "Describe the app change"
git push origin main
git tag v1.0.1
git push origin v1.0.1
```

The app checks for releases on app launch no more often than every 12 hours. On finding a newer release, choose **Download & Install**. Android may require enabling **Install unknown apps** for SBE Quotations and always asks you to approve the update.

## First-run Android permissions

To save at the requested internal-storage root, Android 11+ requires the user to explicitly enable **All files access** for this sideloaded app. The app prompts for it on first PDF save and creates `/storage/emulated/0/Quotitions Form`. This permission is for direct internal distribution; a Google Play publication would need a storage approach compatible with Play's restricted all-files-access policy.

History and customer master are kept locally in app WebView storage. Android cloud auto-backup is disabled; use **Settings → Export Data Backup (JSON)** to save a small portable backup before changing phones or clearing app data.

## Source layout

- `app/src/main/assets/index.html` — responsive quotation UI, customer master, paste parsing, history and settings.
- `app/src/main/java/com/sidhbaba/quotations/MainActivity.java` — Android bridge, storage/save/share and GitHub OTA updates.
- `app/src/main/java/com/sidhbaba/quotations/QuotePdfRenderer.java` — multi-page PDF layout, table, terms, signature and footer.
- `app/src/main/assets/quotation_background.png` — high-resolution quotation template background from the supplied filled sample PDF, including its fixed text and authorised signature.
- `.github/workflows/build-apk.yml` — debug artifact and signed tagged-release build.
