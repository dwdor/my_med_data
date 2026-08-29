# MedVault – Spezifikation

Arbeitstitel: **MedVault**. Persönliche App zum lokalen, verschlüsselten Speichern und
Durchsuchen sensibler medizinischer Dokumente (Arztbriefe, Laborbefunde, Bildgebung,
Impfnachweise etc.).

Dieses Dokument hält alle Entscheidungen fest, die in der Klärungsrunde getroffen wurden,
und dient als Grundlage für die Implementierung. Es wird laufend aktualisiert, wenn sich
Anforderungen ändern.

## 1. Ziele & Nicht-Ziele

**Ziele**
- Sensible medizinische Dokumente ausschließlich lokal auf dem Gerät speichern, stark
  verschlüsselt, mit biometrischer Zugangssperre.
- Dokumente schnell wiederfinden über Volltextsuche (inkl. OCR bei Scans/Fotos) kombiniert
  mit Kategorien/Tags/Metadaten.
- Einfacher Import aus Mail-Anhängen, Downloads-Ordner oder direkt per Kamera-Scan.
- Mehrere Profile (z. B. Familienmitglieder) innerhalb einer App-Instanz verwalten.
- Einzelne Dokumente wieder exportieren/teilen können (z. B. an eine Arztpraxis).
- Verschlüsseltes Backup/Restore für Gerätewechsel, ohne Cloud-Zwang.

**Nicht-Ziele (MVP)**
- Kein zertifiziertes Medizinprodukt, keine Diagnoseunterstützung, keine
  Medikamenten-Interaktionsprüfung.
- Keine Cloud-Synchronisierung, kein Server-Backend, keine Internetverbindung zur Laufzeit
  erforderlich (App fordert bewusst **keine** `INTERNET`-Berechtigung an).
- Keine Mehrbenutzer-Trennung mit eigenen Zugangsdaten pro Profil (Profile sind
  Datenpartitionen innerhalb einer verschlüsselten App-Instanz, nicht eigene Konten – siehe
  Abschnitt 6.5 für Rationale und spätere Erweiterungsoption).
- Keine native Layout-Darstellung von Word/.docx-Dokumenten im MVP (siehe 6.4).

## 2. Zielplattform

- **Jetzt:** Android, nativ (Kotlin + Jetpack Compose). `minSdk 26` (Android 8.0), `targetSdk`
  jeweils aktuell.
- **Später:** iOS, siehe Abschnitt 9 (eigene Spezifikation, gleiches Datenmodell/Feature-Set).
- Grund für Cross-Platform-Verzicht: native Entwicklung erlaubt tiefere Integration von
  Keystore/Secure Enclave, biometrischer Authentifizierung, On-Device-OCR und
  Dokumentenscanner – für eine Sicherheits-/Datenschutz-App wichtiger als Code-Sharing.

## 3. Tech-Stack (Android)

| Bereich | Wahl |
|---|---|
| Sprache/UI | Kotlin, Jetpack Compose, Material 3 |
| Architektur | MVVM + Repository-Pattern, unidirektionaler Datenfluss |
| DI | Hilt |
| Navigation | Navigation Compose |
| Persistenz | Room über SQLCipher (verschlüsselte SQLite-DB) |
| Volltextsuche | SQLite FTS4-Virtual-Table (Titel, Notizen, extrahierter Text) |
| Dateiverschlüsselung | AndroidX Security Crypto (`MasterKey` + `EncryptedFile`, Keystore-gestützt) |
| Biometrie | AndroidX Biometric (`BiometricPrompt` + `CryptoObject`, kryptografisch an Keystore-Key gebunden, nicht nur UI-Gate) |
| OCR | ML Kit Text Recognition (on-device, kein Netzwerk) |
| Dokumentenscanner | ML Kit Document Scanner (`GmsDocumentScanning`, Kantenerkennung, Mehrseiten-PDF) |
| Hintergrundverarbeitung | WorkManager (OCR/Text-Extraktion läuft asynchron nach Import) |
| Bild-Rendering | Coil (Compose) |
| PDF-Rendering | `android.graphics.pdf.PdfRenderer` (Bordmittel, kein Netzwerk) |

Alle Bibliotheken laufen vollständig on-device; die App fragt keine Internet-Berechtigung an.

> **Hinweis zur Implementierung in dieser Umgebung:** Der Code in `android/` wurde ohne
> Android SDK/Emulator geschrieben (in dieser Cloud-Umgebung nicht verfügbar, `dl.google.com`
> ist blockiert). Er ist **nicht kompiliert/getestet worden**. Beim ersten Öffnen in Android
> Studio: Gradle-Sync durchführen, von Android Studio vorgeschlagene Versions-Updates für
> Compose BOM/AGP/Kotlin/Hilt/SQLCipher übernehmen und Build-Fehler wie gewohnt beheben.

## 4. Sicherheitsarchitektur

Zwei unabhängige Verschlüsselungsebenen, beide an einen Android-Keystore-Schlüssel gebunden,
der nur nach erfolgreicher biometrischer Authentifizierung (oder Geräte-PIN als Fallback,
`setUserAuthenticationRequired`) nutzbar ist:

1. **Datenbank:** Room-DB wird mit SQLCipher verschlüsselt. Die DB-Passphrase ist ein
   zufälliger 256-Bit-Wert, der selbst mit einem Keystore-AES-Key verpackt (wrapped) und in
   `EncryptedSharedPreferences` abgelegt wird.
2. **Originaldateien:** jede hochgeladene/gescannte Datei wird einzeln über `EncryptedFile`
   (AES-256-GCM, Keystore-`MasterKey`) im App-privaten Speicher abgelegt (nicht im öffentlichen
   Speicher, nicht in der Medien-Galerie sichtbar).

**App-Sperre:**
- Beim App-Start bzw. nach Rückkehr aus dem Hintergrund (sofortiges Sperren, kein Timeout im
  MVP) wird ein `BiometricPrompt` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) angezeigt.
- Die beiden Keystore-Schlüssel (DB-Passphrase-Wrap-Key, Datei-`MasterKey`) sind mit
  `setUserAuthenticationRequired(true)` + `setUserAuthenticationValidityDurationSeconds(...)`
  konfiguriert: der Keystore selbst verweigert jede Nutzung dieser Schlüssel, wenn nicht
  innerhalb des Gültigkeitsfensters eine erfolgreiche Geräte-Authentifizierung stattgefunden
  hat – das ist eine kryptografische Durchsetzung durch Android Keystore, keine reine
  App-seitige UI-Prüfung. Das Gültigkeitsfenster wird bei jeder Rückkehr in den Vordergrund
  durch den erneuten `BiometricPrompt` aufgefrischt.
- Fallback ohne enrollte Biometrie: Geräte-Zugangscode (im selben `BiometricPrompt`-Aufruf).

**Keine Netzwerk-Angriffsfläche:** ohne `INTERNET`-Permission kann die App selbst bei
Kompromittierung keine Daten exfiltrieren; das ist eine bewusste Design-Entscheidung.

## 5. Datenmodell

```
Profile
  id, name, colorTag, createdAt

Document
  id, profileId → Profile
  title, category (Enum), doctorOrInstitution, documentDate, importedAt
  storedFileName (Referenz auf verschlüsselte Datei), mimeType
  ocrStatus (PENDING | DONE | FAILED | NOT_APPLICABLE), notes

Tag
  id, name

DocumentTagCrossRef
  documentId → Document, tagId → Tag   (n:m)

DocumentText  (FTS4 virtual table)
  documentId, title, notes, extractedText
```

**Feste Kategorien (Enum, erweiterbar per Code-Änderung):**
`LABORBEFUND`, `ARZTBRIEF`, `BILDGEBUNG` (Röntgen/CT/MRT/Ultraschall), `MEDIKATION`
(Rezepte/Medikationspläne), `IMPFUNG`, `SONSTIGES`.

Freie Tags ergänzen die Kategorien (z. B. „Kind A“, „2024“, „Kardiologie“ – Profile decken die
Personen-Trennung ab, Tags sind für alles andere).

## 6. Kernfunktionen

### 6.1 Import
Drei gleichwertige Wege, alle münden im selben Import-Flow (Metadaten erfassen → Kategorie
wählen → Tags optional → Datei wird verschlüsselt abgelegt → OCR-Job wird eingeplant):
- **Teilen-Funktion:** `ShareReceiverActivity` mit Intent-Filtern für `ACTION_SEND` und
  `ACTION_SEND_MULTIPLE`, MIME-Typen `application/pdf`, `image/*`,
  `application/vnd.openxmlformats-officedocument.wordprocessingml.document`. So kann aus der
  Mail-App oder dem Dateien-Manager heraus direkt „Teilen an MedVault“ gewählt werden.
- **Manueller Datei-Import:** Storage Access Framework (`ACTION_OPEN_DOCUMENT`,
  Mehrfachauswahl), z. B. aus dem Downloads-Ordner.
- **Kamera-Scan:** ML Kit Document Scanner – automatische Kantenerkennung, Perspektivkorrektur,
  Mehrseiten-Dokumente werden zu einer PDF zusammengefasst, Filter (z. B. Graustufen) wählbar.

### 6.2 Suche
Kombinierte Suche (wie in der Klärung festgelegt):
- Volltextsuche über OCR-Text (Scans/Fotos/PDF) und docx-Text via FTS4.
- Filter über Kategorie, Tags, Profil, Zeitraum, Arzt/Institution.
- Eine Suchleiste, die Freitext gegen FTS matcht, kombinierbar mit Filter-Chips.

### 6.3 Dokumentenverwaltung
Liste (gruppiert/sortierbar nach Datum), Detailansicht mit Viewer (PDF via `PdfRenderer`,
Bilder via Coil), Metadaten editierbar, Löschen mit Bestätigung, Teilen als Original-Datei
über `FileProvider` + `ACTION_SEND`.

### 6.4 Word/.docx-Dokumente
Im MVP wird aus `.docx` nur der reine Text extrahiert (leichte XML-Extraktion aus
`word/document.xml`, keine Abhängigkeit von schweren Bibliotheken wie Apache POI, die für
Android nicht optimiert sind) und für die Volltextsuche indexiert. Die Originaldatei bleibt
verschlüsselt gespeichert und ist über „Teilen“ exportierbar; eine formatgetreue Inline-Ansicht
ist eine spätere Erweiterung.

### 6.5 Profile (Familienmitglieder)
Ein Profil-Switcher trennt Dokumente nach Person. Alle Profile liegen in derselben
verschlüsselten App-Instanz und teilen sich eine App-Sperre (ein Satz Biometrie/PIN sperrt
alles). Das ist eine bewusste MVP-Vereinfachung: einzelne Profile mit eigenen Zugangsdaten
abzusichern (z. B. für Jugendliche mit eigenem Bereich) ist als spätere Erweiterung denkbar,
aber nicht Teil des MVP.

### 6.6 Backup & Restore
- Export: Datenbank-Inhalt + alle Originaldateien werden in ein ZIP gepackt und mit einem vom
  Nutzer vergebenen Backup-Passwort (PBKDF2 → AES-256-GCM, unabhängig von der Geräte-Biometrie,
  damit ein Restore auf einem neuen Gerät möglich ist) verschlüsselt. Speicherort wird per SAF
  frei gewählt (z. B. lokaler Ordner, SD-Karte, vom Nutzer verwalteter Cloud-Ordner – die App
  selbst spricht keine Cloud-API an).
- Restore: Backup-Datei auswählen, Passwort eingeben, Entschlüsselung + Import in eine neue
  bzw. bestehende Installation.

## 7. Berechtigungen (Android)

- `CAMERA` (nur für den Dokumentenscanner)
- Kein `INTERNET`
- Kein breiter Medien-Zugriff nötig für den Kernpfad; Bild-/Datei-Zugriff läuft über SAF
  (`ACTION_OPEN_DOCUMENT`), das keine Laufzeit-Permission erfordert.

## 8. Nicht-funktionale Anforderungen

- Sprache der Oberfläche: Deutsch (Erstversion), Struktur so, dass Lokalisierung später
  einfach ergänzt werden kann (`strings.xml`).
- Barrierefreiheit: Material-3-Standardkomponenten, ausreichende Kontraste, Content-Descriptions
  für Icons.
- Datensparsamkeit: keine Analytics, kein Tracking, keine Drittanbieter-SDKs mit
  Netzwerkzugriff.

## 9. Zukünftige iOS-Implementierung (Spezifikation, noch nicht umgesetzt)

Ziel: identisches Feature-Set und Datenmodell, adaptiert auf iOS-Bordmittel.

| Bereich | Android (jetzt) | iOS (später) |
|---|---|---|
| UI | Jetpack Compose | SwiftUI |
| Architektur | MVVM + Repository | MVVM + Repository (gleiche Domain-Schicht-Logik, getrennt implementiert) |
| Persistenz | Room + SQLCipher | Core Data oder GRDB, jeweils über SQLCipher for iOS verschlüsselt |
| Dateiverschlüsselung | AndroidX Security Crypto (Keystore) | `NSFileProtectionComplete` + zusätzliche AES-Verschlüsselung über Schlüssel aus der Secure Enclave (`CryptoKit`) |
| Biometrie | BiometricPrompt (Keystore-gebunden) | `LocalAuthentication` (Face ID/Touch ID), Schlüsselzugriff über `SecAccessControl` mit `.biometryCurrentSet` |
| OCR | ML Kit Text Recognition | `Vision`-Framework (`VNRecognizeTextRequest`), on-device, kein Netzwerk |
| Dokumentenscanner | ML Kit Document Scanner | `VisionKit` `VNDocumentCameraViewController` (identisches Verhalten: Kantenerkennung, Mehrseiten-PDF) |
| Import Share-Sheet | `ShareReceiverActivity` (Intent-Filter) | Share Extension (App Extension Target) |
| Datei-Import | SAF | `UIDocumentPickerViewController` |
| Hintergrundverarbeitung | WorkManager | `BackgroundTasks` Framework (`BGProcessingTask`) |
| Backup-Format | Verschlüsseltes ZIP (SAF-Export) | Gleiches Backup-Dateiformat (Cross-Plattform-Restore als Ziel!), Export über `UIActivityViewController`/Files-App |

**Designentscheidung:** Das Backup-Dateiformat (verschlüsseltes ZIP, siehe 6.6) wird
plattformunabhängig spezifiziert, damit ein Umstieg von Android auf iOS (oder umgekehrt) über
Export/Restore möglich ist, ohne dass beide Plattformen gleichzeitig laufen müssen.

Diese Tabelle ist die Grundlage für ein eigenes `SPEC-iOS.md`, sobald die iOS-Implementierung
tatsächlich angegangen wird; bis dahin dient sie als Architektur-Leitplanke, damit das
Android-Datenmodell (Abschnitt 5) keine Android-spezifischen Konzepte enthält, die sich nicht
1:1 übertragen lassen.

## 10. Roadmap

**MVP (dieser Implementierungsstand):**
Profile, Import (alle drei Wege), OCR + kombinierte Suche, Kategorien/Tags, Dokumenten-Viewer
und -Teilen, App-Sperre, Backup/Restore.

**Mögliche spätere Erweiterungen (nicht im aktuellen Scope):**
- Erinnerungen/Wiedervorlage (z. B. „nächste Vorsorgeuntersuchung“).
- Formatgetreue docx-Ansicht.
- Eigene Zugangscodes pro Profil.
- Zusätzliche/benutzerdefinierte Kategorien.
- Lokalisierung (Englisch etc.).
- iOS-App gemäß Abschnitt 9.
