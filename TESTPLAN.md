# MedVault – Testspezifikation (Android, Android Studio)

Diese Spezifikation ist für einen Test-Agenten geschrieben, der das in `android/` liegende
MedVault-Artefakt (siehe [SPEC.md](SPEC.md)) in Android Studio zum ersten Mal baut, startet und
funktional durchprüft. Der Code wurde ohne Android SDK/Emulator geschrieben (siehe
[README.md](README.md)) — **niemand hat ihn bisher gebaut oder ausgeführt**. Diese Testrunde ist
also die erste reale Verifikation, nicht nur eine Regressionsprüfung.

## 1. Zweck & Vorgehen

Ziel ist, in der Reihenfolge der Abschnitte 4–5 vorzugehen: zuerst Build-Fähigkeit herstellen,
dann Schicht für Schicht (Entsperren → Profile → Import → Suche → Detail → Löschen/Teilen →
Backup → Sicherheits-/Negativfälle) verifizieren. Jeder gefundene Fehler wird sofort im
Quellcode behoben (Datei:Zeile in SPEC.md/README.md sind bekannte Risikobereiche, siehe
Abschnitt 6) und der betroffene Testfall danach wiederholt, bevor zum nächsten übergegangen
wird — nicht erst am Ende alle Befunde sammeln.

Für jeden Testfall gilt: **Priorität** P0 = blockiert alles Weitere, muss vor Fortsetzung
behoben werden; P1 = Kernfunktion, muss vor Abschluss behoben werden; P2 = Detail/Politur, kann
als bekannter Punkt dokumentiert werden, wenn die Zeit knapp ist.

## 2. Testumgebung einrichten

### 2.1 Android Studio / SDK

- Android Studio (aktuelle stabile Version), JDK 17 (bereits Projektvorgabe, siehe
  `android/app/build.gradle.kts`).
- Projekt `android/` öffnen, Gradle-Sync laufen lassen. **Alle von Android Studio
  vorgeschlagenen Versions-Updates übernehmen** (AGP, Kotlin, Compose BOM, Hilt, KSP, SQLCipher,
  ML Kit, WorkManager, Navigation) — die Versionen in `android/gradle/libs.versions.toml` wurden
  ohne Zugriff auf die echten Repositories gewählt (siehe README.md) und sind als Startpunkt,
  nicht als Fixpunkt zu verstehen.

### 2.2 Emulator

- Ein AVD mit **Google Play**-Systemabbild (nicht nur „Google APIs“) verwenden, API-Level ≥ 30 —
  wird für `play-services-mlkit-document-scanner` (Kamera-Scan) und für ein realistisches
  `BiometricPrompt`-Verhalten benötigt.
- Fingerabdruck auf dem Emulator einrichten, bevor Unlock-Tests beginnen:
  ```
  adb shell locksettings set-pin 1234
  # In den Emulator-Settings: Sicherheit > Fingerabdruck > Einrichten, dann bei "Sensor berühren":
  adb -e emu finger touch 1
  # ggf. mehrfach wiederholen, bis die Einrichtung abgeschlossen ist
  ```
- Für spätere Tests einen erfolgreichen Scan simulieren mit `adb -e emu finger touch 1`, einen
  **nicht erkannten** Finger mit einer anderen ID, z. B. `adb -e emu finger touch 2`.
- Für mehrere laufende Emulatoren/Geräte `-s <serial>` statt `-e` verwenden.

### 2.3 Physisches Gerät (empfohlen zusätzlich zum Emulator)

- Für TC-IMPORT-SCAN-* (Kamera-Dokumentenscanner) und eine realistische Prüfung des
  Biometrie-Verhaltens ist ein echtes Gerät aussagekräftiger als der Emulator. Falls verfügbar,
  mindestens die Kamera-Scan- und Unlock-Testfälle zusätzlich dort ausführen.

### 2.4 Testdaten vorbereiten

Vor Testbeginn bereitstellen (z. B. via `adb push <datei> /sdcard/Download/` oder als
Gmail-Anhang an ein Test-Konto auf dem Testgerät):

| Datei | Zweck |
|---|---|
| `test-textpdf.pdf` | PDF mit echtem Text-Layer (z. B. ein exportiertes Textdokument) | 
| `test-scanpdf.pdf` | PDF, das nur ein eingescanntes Bild ohne Text-Layer enthält (prüft OCR-Pfad) |
| `test-befund.jpg` / `.png` | Fotografiertes/gescanntes Dokument als Bilddatei |
| `test-brief.docx` | Word-Dokument mit mehreren Absätzen Text |
| `test-unsupported.txt` | Nicht unterstützter Dateityp, für Negativtest beim manuellen Datei-Import |

Falls keine echten Beispieldokumente verfügbar sind, genügen frei erfundene Testinhalte (keine
echten Patientendaten verwenden) — wichtig ist nur ein echter Text-Layer bei einer PDF und
keiner bei der anderen, um beide OCR-Pfade abzudecken.

## 3. Traceability

Jeder Testfall referenziert die primär betroffene(n) Quelldatei(en) unter
`android/app/src/main/java/com/medvault/app/…`, damit Befunde direkt zugeordnet werden können.

## 4. Testreihenfolge (Übersicht)

1. Build & Static Checks (Abschnitt 5.1)
2. Erststart / Onboarding, Entsperren (5.2)
3. Profile (5.3)
4. Import: Teilen-Funktion, Datei-Picker, Kamera-Scan (5.4)
5. OCR/Text-Extraktion (5.5)
6. Suche & Filter (5.6)
7. Dokument-Detail: Viewer, Bearbeiten, Löschen (5.7)
8. Teilen/Export eines Dokuments (5.8)
9. Backup Export/Restore (5.9)
10. Sicherheits- & Negativfälle (5.10)
11. Berechtigungen (5.11)

## 5. Testfälle

### 5.1 Build & Static Checks

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-BUILD-01 | P0 | Gradle-Sync in Android Studio ausführen. | Sync erfolgreich, keine „Plugin/Dependency not found“-Fehler. Bei Fehlern: Versionen in `libs.versions.toml` gemäß Studio-Vorschlag anpassen. |
| TC-BUILD-02 | P0 | `./gradlew :app:assembleDebug` bzw. Studio-Build ausführen. | Build erfolgreich. Besonderes Augenmerk auf KSP-Fehler in Room-DAOs (`data/local/dao/TagDao.kt` – Default-Methode `setTagsForDocument`, siehe SPEC.md-Hinweis) und Hilt-Codegen. |
| TC-BUILD-03 | P1 | Merged Manifest (Studio: `AndroidManifest.xml` → Tab „Merged Manifest“) öffnen. | Keine `android.permission.INTERNET` in der finalen Manifest-Zusammenführung (auch nicht via Transitiv-Abhängigkeit, z. B. Play-Services-ML-Kit). Falls doch vorhanden: Ursache identifizieren und in SPEC.md §3/§8 vermerken. |
| TC-BUILD-04 | P1 | App auf Emulator/Gerät installieren und starten. | App startet ohne Absturz, zeigt Entsperr-Bildschirm (`ui/unlock/UnlockScreen.kt`). |
| TC-BUILD-05 | P2 | Lint ausführen (`./gradlew :app:lintDebug`). | Report sichten; keine kritischen (Error-Level) Findings offen lassen. |

### 5.2 Erststart / Entsperren

Betroffene Dateien: `crypto/CryptoManager.kt`, `crypto/UnlockManager.kt`,
`data/local/DatabaseHolder.kt`, `ui/unlock/UnlockScreen.kt`, `MainActivity.kt`.

| ID | Prio | Vorbedingung | Schritte | Erwartung |
|---|---|---|---|---|
| TC-UNLOCK-01 | P0 | Frischinstallation, Fingerabdruck enrollt (2.2). | App starten. | `BiometricPrompt` erscheint automatisch (kein zusätzlicher Klick nötig). |
| TC-UNLOCK-02 | P0 | wie oben | `adb -e emu finger touch 1` (korrekter Finger). | Prompt schließt, App navigiert zu Profilübersicht. Kein Crash, keine Exception in Logcat. |
| TC-UNLOCK-03 | P1 | Erststart, kein Passphrase in `medvault_secure_prefs` vorhanden. | Erfolgreiche Authentifizierung (TC-UNLOCK-02). | Datenbank wird neu angelegt (`getDatabasePath` zeigt `medvault.db`), kein Fehler beim ersten Öffnen (Erstlauf-Pfad: `setUpNewPassphrase()`). |
| TC-UNLOCK-04 | P1 | App bereits entsperrt, im Vordergrund. | App in den Hintergrund schicken (Home-Taste) und wieder öffnen. | App zeigt erneut den Entsperr-Bildschirm — **keine** Anzeige der zuvor sichtbaren Profil-/Dokumentenliste ohne erneute Authentifizierung (`MainActivity.onStop()` → `unlockManager.lock()`). |
| TC-UNLOCK-05 | P1 | wie oben | Falscher Finger: `adb -e emu finger touch 2`. | Fehlermeldung wird angezeigt (`UnlockUiState.Error`), App bleibt gesperrt, kein Crash. |
| TC-UNLOCK-06 | P2 | Gerät/Emulator ohne enrollte Biometrie und ohne Bildschirmsperre. | App starten. | Text aus `unlock_no_biometrics` wird angezeigt statt eines Crashes; „Entsperren“-Button erneut antippen führt zum selben Hinweis, nicht zu einem Absturz. |
| TC-UNLOCK-07 | P2 | Nur Geräte-PIN eingerichtet, keine Biometrie. | App starten, Prompt erscheint. | Geräte-PIN-Eingabe wird als gültiger Entsperrweg akzeptiert (`DEVICE_CREDENTIAL`-Fallback). |
| TC-UNLOCK-08 | P1 | App entsperrt, mehrere Dokumente vorhanden. | Prozess über „Übersicht/Recents“ beenden (Force-Stop) und App neu öffnen. | Erneute Authentifizierung erforderlich; alle zuvor importierten Dokumente sind nach Entsperren weiterhin vorhanden (Passphrase-Unwrap funktioniert über einen Prozessneustart hinweg). |
| TC-UNLOCK-09 | P2 (Langläufer) | `CryptoManager.AUTH_VALIDITY_SECONDS` temporär in einer Debug-Build-Variante auf z. B. `30` setzen, neu bauen. | Entsperren, 35 Sekunden warten (App im Vordergrund lassen, NICHT in den Hintergrund schicken), dann ein Dokument öffnen oder importieren. | Erwartetes Verhalten laut Code-Kommentar ist ungeklärt: entweder wird `UserNotAuthenticatedException` sauber behandelt (z. B. erneuter Prompt) oder die Aktion schlägt sichtbar fehl. **Beides ist aktuell nicht implementiert** (kein Catch für `UserNotAuthenticatedException` außerhalb des initialen Unlock) — voraussichtlich ein echter Bug. Nach Testende Konstante wieder auf `3600` zurücksetzen. |

### 5.3 Profile

Betroffene Dateien: `ui/profiles/ProfileListScreen.kt`, `ui/profiles/ProfileListViewModel.kt`,
`data/repository/ProfileRepository.kt`.

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-PROFILE-01 | P0 | Nach Erstentsperren: FAB antippen, Name „Test Person“ eingeben, Farbe wählen, speichern. | Profil erscheint in der Liste mit gewählter Farbe. |
| TC-PROFILE-02 | P1 | Zweites Profil „Kind A“ anlegen. | Beide Profile sichtbar, unabhängig voneinander antippbar. |
| TC-PROFILE-03 | P1 | Profil antippen. | Navigiert zur (leeren) Dokumentenliste dieses Profils (`documents_empty`-Text sichtbar). |
| TC-PROFILE-04 | P2 | Namensfeld beim Anlegen leer lassen. | „Speichern“-Button bleibt deaktiviert (siehe `enabled = name.isNotBlank()`). |
| TC-PROFILE-05 | P1 | Ein Profil mit bereits importierten Dokumenten löschen (Lösch-Weg über Repository/DB-Inspektor, da UI aktuell keinen Lösch-Button für Profile hat — **prüfen, ob das eine gewollte Lücke ist**). | Zugehörige Dokumente werden durch `ForeignKey.CASCADE` mitgelöscht (in der DB, nicht die verschlüsselten Dateien auf Platte — **das ist eine Lücke**: `EncryptedFileStore`-Dateien verwaister Dokumente bleiben aktuell auf der Platte liegen, siehe `data/repository/ProfileRepository.kt::deleteProfile`). Als Befund dokumentieren, nicht selbstständig „stillschweigend“ übergehen. |

### 5.4 Import

Betroffene Dateien: `importing/*`, `ui/importflow/*`, `ocr/TextExtractionWorker.kt`.

**Teilen-Funktion (Share-Sheet)**

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-IMPORT-SHARE-01 | P0 | `test-textpdf.pdf` per Mail-App/Dateien-App an MedVault „teilen“, während App gesperrt/nicht laufend ist. | `ShareReceiverActivity` kopiert Datei in den Cache, `MainActivity` startet, Entsperr-Bildschirm erscheint zuerst. |
| TC-IMPORT-SHARE-02 | P0 | Nach Entsperren (Fortsetzung von 01): ein Profil antippen. | Statt Dokumentenliste öffnet sich direkt der Import-Bildschirm mit vorbelegter Quelle (`Destinations.ImportMode.SHARED`). Titelfeld ggf. leer, Formular ausfüllbar. |
| TC-IMPORT-SHARE-03 | P1 | Import-Formular ausfüllen (Titel, Kategorie, Arzt, Datum `2024-01-15`, Tags „Test, Labor“), speichern. | Dokument erscheint in der Liste des gewählten Profils; Datei liegt verschlüsselt unter `filesDir/documents/` (nicht lesbar als Klartext via `adb shell cat`). |
| TC-IMPORT-SHARE-04 | P1 | Bild (`test-befund.jpg`) teilen. | Gleicher Ablauf, MIME `image/*` wird akzeptiert. |
| TC-IMPORT-SHARE-05 | P1 | `.docx`-Datei teilen. | Wird akzeptiert (MIME `application/vnd.openxmlformats-officedocument.wordprocessingml.document`). |
| TC-IMPORT-SHARE-06 | P2 | Nicht unterstützten Dateityp (z. B. `.txt`) über eine App teilen, die dennoch „An MedVault teilen“ anbietet (sofern das OS das zulässt). | MedVault erscheint idealerweise gar nicht erst im Share-Sheet für nicht deklarierte MIME-Typen (Intent-Filter in `AndroidManifest.xml` prüfen); falls doch aufgerufen, darf es nicht abstürzen. |
| TC-IMPORT-SHARE-07 | P2 | Mehrere Bilder gleichzeitig teilen (`ACTION_SEND_MULTIPLE`). | Bekannte MVP-Einschränkung laut Code-Kommentar: nur die erste Datei wird verarbeitet. Verifizieren, dass zumindest die erste korrekt ankommt und kein Crash auftritt; fehlende Mehrfachverarbeitung ist erwartetes Verhalten, nicht neu als Bug melden. |

**Manueller Datei-Import**

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-IMPORT-FILE-01 | P0 | In der Dokumentenliste eines Profils: FAB → „Datei auswählen“. | System-Dateipicker (SAF) öffnet sich, gefiltert auf PDF/Bild/DOCX. |
| TC-IMPORT-FILE-02 | P0 | `test-textpdf.pdf` auswählen. | Import-Formular öffnet sich, Titel wird aus Dateiname vorbelegt (`setDefaultTitle`). |
| TC-IMPORT-FILE-03 | P2 | Datei-Picker öffnen, dann zurück/abbrechen ohne Auswahl. | Import-Bildschirm zeigt „Quelle wird ausgewählt…“ weiterhin an bzw. Nutzer kann sinnvoll zurücknavigieren, kein Absturz, kein „leeres“ Speichern möglich (Fehlertext „Keine Datei ausgewählt“ bei Speichern-Versuch). |
| TC-IMPORT-FILE-04 | P2 | Ungültiges Datum eingeben (z. B. „gestern“) und speichern. | Datum wird stillschweigend als `null` behandelt (`runCatching { LocalDate.parse(it) }.getOrNull()`), kein Crash — aber **prüfen, ob das UX-technisch akzeptabel ist** (keine Fehlermeldung an den Nutzer) und als P2-Verbesserung vormerken. |

**Kamera-Scan**

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-IMPORT-SCAN-01 | P0 | FAB → „Mit Kamera scannen“ (auf Gerät/Emulator mit Google-Play-Image). | ML-Kit-Dokumentenscanner-UI öffnet sich (Kantenerkennung aktiv). |
| TC-IMPORT-SCAN-02 | P1 | Erste Nutzung: Play Services lädt ggf. das Scanner-Modul nach. | **Hinweis**: Dafür ist einmalig eine echte Internetverbindung *auf Play-Services-Ebene* nötig, auch wenn MedVault selbst kein `INTERNET`-Recht hat — das ist erwartetes Verhalten, nicht als Verstoß gegen SPEC.md §3 werten. Falls kein Internet verfügbar: Fehlerpfad prüfen (aktuell kein UI-Feedback bei `addOnFailureListener`, siehe `ui/importflow/ImportScreen.kt` — Formular öffnet sich einfach ohne Quelle; als Befund vermerken). |
| TC-IMPORT-SCAN-03 | P0 | Ein- und mehrseitiges Dokument scannen, „Fertig“. | PDF mit korrekter Seitenzahl wird erzeugt und als Quelle im Formular übernommen. |
| TC-IMPORT-SCAN-04 | P1 | Im Scanner abbrechen/zurück. | Import-Bildschirm bleibt ohne Quelle, kein Crash; Speichern zeigt Fehlermeldung. |
| TC-IMPORT-SCAN-05 | P1 | Gescanntes Dokument speichern. | Wird korrekt als `application/pdf` importiert, Titel-Default „Scan `<Datum>`“ sichtbar, falls Titel leer gelassen. |

### 5.5 OCR / Text-Extraktion

Betroffene Dateien: `ocr/TextExtractionWorker.kt`, `ocr/DocxTextExtractor.kt`.

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-OCR-01 | P0 | `test-scanpdf.pdf` (nur Bild, kein Text-Layer) importieren. | `ocrStatus` startet als „PENDING“ (Hinweistext in der Liste), WorkManager-Job läuft (in Logcat/`adb shell dumpsys jobscheduler` oder Studios „App Inspection → Background Task Inspector“ sichtbar), wechselt nach Abschluss zu „DONE“, kein sichtbarer Pending-Hinweis mehr. |
| TC-OCR-02 | P0 | Nach Abschluss von TC-OCR-01: in der Suche nach einem im Scan enthaltenen Wort suchen. | Dokument wird gefunden (bestätigt, dass ML-Kit-Text tatsächlich extrahiert und in FTS indexiert wurde). |
| TC-OCR-03 | P1 | `test-textpdf.pdf` (mit echtem Text-Layer) importieren. | Wird ebenfalls über den Bild-Rendering-Pfad (`PdfRenderer` + ML Kit) verarbeitet, nicht über den PDF-Text-Layer direkt — Ergebnis sollte trotzdem den sichtbaren Text enthalten; Qualität/Vollständigkeit im Vergleich zum Originaltext prüfen. |
| TC-OCR-04 | P1 | `test-brief.docx` importieren. | Kein OCR (kein Bild-Rendering), sondern reine XML-Textextraktion (`DocxTextExtractor`); extrahierter Text erscheint sowohl in der Volltextsuche als auch im Dokument-Detail (statt eines Bild-Viewers, siehe TC-DETAIL-04). |
| TC-OCR-05 | P2 | Sehr große mehrseitige PDF (z. B. 15+ Seiten) scannen/importieren. | Verarbeitung dauert entsprechend länger, aber App bleibt bedienbar (Worker läuft im Hintergrund, kein UI-Freeze); Speicher-/Absturzverhalten beobachten (Bitmap-Rendering pro Seite, `RENDER_SCALE = 2`). |
| TC-OCR-06 | P2 | Import eines Dokuments erzwingen, bei dem die Extraktion fehlschlägt (z. B. absichtlich beschädigte PDF-Datei einschleusen). | `ocrStatus` wird „FAILED“, entsprechender Hinweistext (`document_ocr_failed`) erscheint in Liste/Detail, Dokument bleibt aber über Titel/Tags auffindbar und ist nicht verloren. |

### 5.6 Suche & Filter

Betroffene Dateien: `ui/documents/DocumentListViewModel.kt`, `data/repository/DocumentRepository.kt`.

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-SEARCH-01 | P0 | Mind. 3 Dokumente mit unterschiedlichen Kategorien/Tags/Titeln importiert. Suchbegriff aus dem Titel eingeben. | Nur passende Dokumente erscheinen, Liste aktualisiert sich mit kurzer Verzögerung (Debounce 250 ms). |
| TC-SEARCH-02 | P0 | Kategorie-Filter-Chip antippen. | Liste filtert korrekt auf diese Kategorie; erneutes Antippen desselben Chips oder „Alle“ hebt den Filter auf. |
| TC-SEARCH-03 | P1 | Tag-Chip antippen (z. B. „Labor“). | Liste zeigt nur Dokumente mit diesem Tag; erneutes Antippen deaktiviert den Filter (Toggle-Verhalten in `onTagChange`). |
| TC-SEARCH-04 | P1 | Suchbegriff + Kategorie-Filter + Tag-Filter gleichzeitig setzen, so dass sich die Ergebnismengen überschneiden. | Ergebnis ist die korrekte Schnittmenge (siehe `DocumentRepository.search` – FTS-IDs ∩ Tag-IDs). |
| TC-SEARCH-05 | P1 | Suchbegriff mit Umlauten/Sonderzeichen (z. B. „Röntgen-Befund“) eingeben, wenn ein Dokument diesen Text via OCR enthält. | Da `buildFtsQuery` nur `Char.isLetterOrDigit()` behält, wird nach „röntgenbefund“ gesucht — prüfen, ob das noch sinnvolle Treffer liefert oder ob zusammengesetzte Wörter mit Sonderzeichen Treffer verpasst. Als Befund dokumentieren, falls Erwartung nicht erfüllt. |
| TC-SEARCH-06 | P2 | Suchbegriff eingeben, der zu keinem Dokument passt. | Leere Liste mit `documents_empty`-Text, kein Crash. |
| TC-SEARCH-07 | P2 | Sehr kurzer Suchbegriff (1 Zeichen). | `token*`-Prefix-Suche liefert ggf. viele Treffer, aber keinen Fehler. |

### 5.7 Dokument-Detail: Viewer, Bearbeiten, Löschen

Betroffene Dateien: `ui/documents/DocumentDetailScreen.kt`, `ui/documents/DocumentDetailViewModel.kt`.

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-DETAIL-01 | P0 | PDF-Dokument aus der Liste öffnen. | Alle Seiten werden als Bilder gerendert und untereinander scrollbar dargestellt (max. 15 Seiten, siehe `MAX_PREVIEW_PAGES`). |
| TC-DETAIL-02 | P0 | Bild-Dokument öffnen. | Bild wird korrekt angezeigt. |
| TC-DETAIL-03 | P1 | `.docx`-Dokument öffnen. | Kein Bild-Viewer, sondern der extrahierte Text wird als Fließtext angezeigt (siehe SPEC.md §6.4 – bewusste MVP-Einschränkung, keine formatgetreue Ansicht). |
| TC-DETAIL-04 | P1 | Metadaten ändern (Titel, Kategorie, Arzt, Datum, Notiz, Tags) und „Speichern“. | Änderungen werden persistiert; zurück zur Liste navigieren und Dokument erneut öffnen bestätigt die Änderung. Tags-Änderung spiegelt sich auch in den Filter-Chips der Liste wider. |
| TC-DETAIL-05 | P1 | Tags ändern, danach in der Dokumentenliste nach dem alten Tag filtern. | Alter Tag-Chip verschwindet, falls kein anderes Dokument ihn mehr nutzt (bzw. bleibt bestehen, falls doch — Verhalten von `TagDao.setTagsForDocument` prüfen). |
| TC-DETAIL-06 | P0 | „Löschen“-Icon antippen, Bestätigungsdialog bestätigen. | Dokument verschwindet aus der Liste; zugehörige verschlüsselte Datei ist von der Platte entfernt (`adb shell ls` im App-Datenverzeichnis, benötigt Root/Debug-Build oder `run-as`); erneute Suche nach dem alten Titel liefert keinen Treffer mehr. |
| TC-DETAIL-07 | P2 | Löschen-Dialog per „Abbrechen“ verlassen. | Dokument bleibt unverändert erhalten. |

### 5.8 Teilen/Export eines Dokuments

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-SHARE-01 | P0 | In der Detailansicht das „Teilen“-Icon antippen. | System-Share-Sheet öffnet sich mit einer Kopie der Originaldatei (nicht der App-internen verschlüsselten Datei). |
| TC-SHARE-02 | P1 | Geteilte Datei an eine andere App (z. B. „In Dateien speichern“ oder „Per Mail senden“) übergeben. | Empfangende App kann die Datei öffnen; Inhalt/MIME-Typ stimmt mit dem Original überein. |
| TC-SHARE-03 | P2 | Mehrmals hintereinander „Teilen“ für unterschiedliche Dokumente antippen. | Alter Cache unter `cacheDir/share/` wächst pro Vorgang (kein automatisches Aufräumen implementiert, siehe `EncryptedFileStore.clearShareCache()` – wird aktuell nirgends aufgerufen) — als P2-Befund vermerken, kein funktionaler Bug, aber Speicherplatz-Hygiene-Lücke. |

### 5.9 Backup Export/Restore

Betroffene Dateien: `backup/BackupManager.kt`, `backup/BackupCrypto.kt`, `ui/settings/*`.

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-BACKUP-01 | P0 | Einstellungen öffnen (Zahnrad-Icon in der Profilübersicht) → „Backup exportieren“ → Passwort eingeben → Speicherort wählen. | Datei wird erfolgreich geschrieben, „Backup erfolgreich exportiert“ erscheint. |
| TC-BACKUP-02 | P1 | Exportierte Datei außerhalb der App öffnen (z. B. Hex-Viewer). | Inhalt ist nicht als Klartext-ZIP lesbar (beginnt nicht mit `PK\x03\x04`, da erst AES-verschlüsselt). |
| TC-BACKUP-03 | P0 | „Backup wiederherstellen“ mit korrektem Passwort und der zuvor exportierten Datei, auf demselben Geräte-/Emulator-Zustand. | Meldung „Wiederherstellung abgeschlossen…“, App danach vollständig beenden und neu starten (manueller Schritt, laut UI-Text erforderlich), anschließend Entsperren funktioniert und alle vorherigen Dokumente sind wieder vorhanden. |
| TC-BACKUP-04 | P0 | Restore mit **falschem** Passwort versuchen. | Fehlermeldung, kein Absturz, bestehende Daten bleiben unangetastet (Restore darf nicht „halb“ durchlaufen und dann fehlschlagen — prüfen: aktuell wird `databaseHolder.close()` + `fileStore.clearAllDocuments()` + DB-Datei-Löschung **vor** der Entschlüsselung mit dem Passwort nicht ausgeführt, da `BackupCrypto.decrypt` ganz am Anfang steht und bei falschem Passwort eine Exception wirft, bevor irgendetwas gelöscht wird — verifizieren, dass das tatsächlich so eintritt). |
| TC-BACKUP-05 | P0 | **Cross-Device-Restore**: Backup auf Gerät A erstellen, Datei auf Gerät B (zweiter Emulator mit frischer Installation) übertragen und dort wiederherstellen. | Dies ist der eigentliche Zweck der Funktion (SPEC.md §6.6) — nach Neustart auf Gerät B müssen Entsperren und alle Dokumente funktionieren, obwohl Gerät B einen komplett anderen Keystore hat (`CryptoManager.installPassphrase`). **Dieser Testfall hat höchste Priorität unter den Backup-Tests**, da er den komplexesten, ungetesteten Teil der Implementierung abdeckt. |
| TC-BACKUP-06 | P1 | Restore-Datei mit absichtlich beschädigten Bytes (z. B. letzte 10 Bytes der exportierten Datei überschreiben) wiederherstellen. | Sauberer Fehler statt Absturz (GCM-Tag-Prüfung sollte fehlschlagen, `BackupCrypto.decrypt` wirft dann eine `AEADBadTagException` o. Ä. — sicherstellen, dass das als `Result.failure` ankommt und nicht als unbehandelte Exception den Prozess killt). |
| TC-BACKUP-07 | P2 | Restore mit einer Datei, die kein MedVault-Backup ist (z. B. beliebige andere Datei). | Fehlermeldung statt Crash. |
| TC-BACKUP-08 | P2 | Backup exportieren, während im Hintergrund noch ein OCR-Job läuft (frisch importiertes Dokument). | Beobachten, ob der laufende Schreibzugriff des WorkManager-Jobs mit dem WAL-Checkpoint/Datei-Kopiervorgang kollidiert (bekannte, in `BackupManager`-Kommentar dokumentierte Grenze: kein atomarer Snapshot). Ergebnis dokumentieren, auch wenn es „meistens funktioniert“. |

### 5.10 Sicherheits- & Negativfälle

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-SECURITY-01 | P0 | App entsperren, dann `adb shell run-as com.medvault.app ls files/documents/` (Debug-Build) und eine der Dateien auslesen. | Dateiinhalt ist nicht als Klartext-PDF/-Bild erkennbar (AES-verschlüsselt via `EncryptedFile`). |
| TC-SECURITY-02 | P0 | `adb shell run-as com.medvault.app cat databases/medvault.db \| head -c 32 \| xxd` (o. Ä.). | Kein erkennbarer SQLite-Header „SQLite format 3“ im Klartext (SQLCipher verschlüsselt den gesamten Dateiinhalt inkl. Header). |
| TC-SECURITY-03 | P1 | Im Android Studio „Network Profiler“ während normaler App-Nutzung (Import, Suche, Backup) beobachten. | Kein Netzwerkverkehr von `com.medvault.app` selbst (ggf. Play-Services-Verkehr für den Dokumentenscanner ist ein separater Prozess/UID, siehe TC-IMPORT-SCAN-02, und zählt nicht als Verstoß). |
| TC-SECURITY-04 | P2 | Dokument-Detailbildschirm mit sensiblem Testinhalt öffnen, Screenshot auslösen bzw. in die „Übersicht/Recents“ wechseln. | **Bekannte Lücke**: Es ist aktuell kein `FLAG_SECURE` gesetzt — ein Screenshot gelingt und das Recents-Vorschaubild zeigt den Dokumenteninhalt. Dies als konkreten, benannten Befund melden (nicht nur „geht nicht“, sondern mit Verweis auf `MainActivity.kt`, wo `WindowManager.LayoutParams.FLAG_SECURE` fehlt) und dem Nutzer zur Entscheidung vorlegen, ob das für ein Medizin-Dokumente-Tool gewünscht ist. |
| TC-SECURITY-05 | P1 | App-Prozess während einer laufenden Aktion (z. B. Import) über Android Studios „Kill Application“ hart beenden, dann neu starten. | Kein dauerhaft inkonsistenter Zustand (z. B. eine „PENDING“-Datenbankzeile ohne zugehörige Datei); zumindest muss die App nach Neuentsperren wieder normal bedienbar sein. |

### 5.11 Berechtigungen

| ID | Prio | Schritte | Erwartung |
|---|---|---|---|
| TC-PERM-01 | P0 | Kamera-Scan zum ersten Mal starten. | System-Dialog zur `CAMERA`-Berechtigung erscheint (Laufzeit-Permission). |
| TC-PERM-02 | P1 | Berechtigung verweigern. | Kein Crash; Nutzer landet zurück im Import-Formular ohne Quelle (analog TC-IMPORT-SCAN-04) — **prüfen, ob überhaupt ein Berechtigungs-Check vor dem Scanner-Start existiert**, da der aktuelle Code (`ImportScreen.kt`) den Scanner-Intent direkt über Play Services startet, ohne selbst `ActivityCompat.requestPermissions` für `CAMERA` aufzurufen. Falls Play Services das intern selbst handhabt, verifizieren, dass zumindest keine `SecurityException` durchschlägt. |
| TC-PERM-03 | P2 | Berechtigung nach Verweigerung über System-Einstellungen wieder erteilen, erneut scannen. | Scanner funktioniert danach normal. |

## 6. Bekannte Risikobereiche (zuerst/besonders sorgfältig prüfen)

Diese Punkte sind im Code bzw. in SPEC.md/README.md bereits als unsicher gekennzeichnet, weil sie
ohne Android SDK/Emulator nicht verifiziert werden konnten. Sie verdienen überdurchschnittlich
viel Testaufmerksamkeit, bevor der Rest als „grundsätzlich stabil“ gilt:

1. **Keystore/Biometrie-Bindung** (`crypto/CryptoManager.kt`, `data/files/EncryptedFileStore.kt`):
   `setUserAuthenticationValidityDurationSeconds` / `MasterKey.setUserAuthenticationRequired`
   API-Verhalten über Enroll/Un-Enroll-Zyklen hinweg (siehe auch TC-UNLOCK-09).
2. **SQLCipher-Integration** (`data/local/DatabaseHolder.kt`, `MedVaultApplication.kt`):
   `SQLiteDatabase.loadLibs()`/`SupportFactory`-API-Pfad der tatsächlich aufgelösten
   `net.zetetic:sqlcipher-android`-Version.
3. **Room-DAO-Default-Methoden** (`data/local/dao/TagDao.kt`, `data/local/dao/SearchDao.kt`):
   Kompiliert KSP eine `@Transaction`-Methode mit Kotlin-Default-Implementierung, die andere
   abstrakte DAO-Methoden aufruft, korrekt?
4. **ExposedDropdownMenuBox/`menuAnchor()`** (`ui/importflow/ImportScreen.kt`,
   `ui/documents/DocumentDetailScreen.kt`): Compose-Material3-API hat sich hier zwischen
   Versionen verändert; ggf. Compiler-Fehler beim Sync, siehe TC-BUILD-01/02.
5. **ML-Kit-Dokumentenscanner** (`importing/DocumentScannerConfig.kt`): Beta-Artefakt,
   Play-Services-Abhängigkeit, siehe TC-IMPORT-SCAN-*.
6. **Backup Cross-Device-Restore** (`backup/BackupManager.kt`): der architektonisch
   komplizierteste Teil der App, siehe TC-BACKUP-05.

## 7. Befund-/Bug-Report-Format

Für jeden gefundenen Fehler bitte in dieser Struktur dokumentieren (z. B. als Liste am Ende des
Testlaufs oder direkt als Commit-Message beim Fix):

```
[TC-ID] Kurztitel
Schweregrad: P0/P1/P2
Datei(en): pfad/zur/Datei.kt:Zeile
Schritte zur Reproduktion: …
Erwartet: …
Tatsächlich: …
Behoben in Commit: <hash> (falls direkt gefixt)
```

## 8. Abgrenzung — nicht Teil dieser Testrunde

- Automatisierte Unit-/Instrumented-Tests werden durch diesen Testplan **nicht ersetzt**, sondern
  als manuelle/exploratve Erstverifikation davor angesiedelt. Für dauerhafte Regressionssicherheit
  sollten aus den P0/P1-Fällen oben anschließend echte `@Test`/Espresso-/Compose-UI-Tests
  abgeleitet werden (aktuell existieren im Projekt keine).
- Mehrsprachigkeit/Lokalisierung (App ist bewusst nur auf Deutsch, siehe SPEC.md §8).
- Verhalten auf Geräten unterhalb `minSdk 26`.
- Last-/Performance-Tests mit sehr vielen (Hunderte) Dokumenten.
