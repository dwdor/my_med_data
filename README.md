# MedVault

Persönliche Android-App zum lokalen, verschlüsselten Speichern und Durchsuchen sensibler
medizinischer Dokumente (Arztbriefe, Laborbefunde, Bildgebung, …). Details zu Anforderungen,
Architektur und Sicherheitskonzept stehen in [SPEC.md](SPEC.md).

## Projektstruktur

- `SPEC.md` – vollständige Spezifikation (Android jetzt, iOS-Architektur für später).
- `android/` – natives Android-Projekt (Kotlin + Jetpack Compose, Gradle).

## Build-Hinweise

Der Code in `android/` wurde **ohne Android SDK/Emulator** geschrieben — diese Cloud-Umgebung
hat keinen Zugriff auf `dl.google.com`/`maven.google.com` und kein installiertes Android SDK, ein
echter Gradle-Build war hier also nicht möglich. Nächste Schritte in Android Studio:

1. `android/` als Projekt öffnen und Gradle-Sync durchlaufen lassen.
2. Von Android Studio vorgeschlagene Versions-Updates (AGP, Kotlin, Compose BOM, Hilt,
   SQLCipher, ML Kit, …) übernehmen — die in `gradle/libs.versions.toml` eingetragenen Versionen
   sind aus der Erinnerung heraus gewählt und nicht gegen die echten Repositories verifiziert.
3. Auf einem echten Gerät/Emulator testen, insbesondere:
   - Biometrie-/Gerätecode-Sperre inkl. Keystore-`setUserAuthenticationRequired`-Verhalten
     (`crypto/CryptoManager.kt`, `data/files/EncryptedFileStore.kt`) — das ist der
     sicherheitskritischste und am wenigsten „von der Stange" nachprüfbare Teil.
   - SQLCipher-Integration (`data/local/DatabaseHolder.kt`, `MedVaultApplication.kt`) gegen die
     tatsächlich aufgelöste `net.zetetic:sqlcipher-android`-Version.
   - ML-Kit-Dokumentenscanner (`importing/DocumentScannerConfig.kt`) auf einem Gerät mit Google
     Play Services.
   - Backup-Export/-Restore (`backup/BackupManager.kt`) end-to-end, inklusive Wiederherstellung
     auf einem zweiten Gerät.

Kurz: Das ist ein vollständig durchgezogenes, aber ungetestetes MVP-Gerüst gemäß SPEC.md — vor
echtem Einsatz mit echten medizinischen Unterlagen unbedingt auf einem realen Gerät verifizieren.
