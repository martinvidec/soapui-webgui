# Betriebshandbuch: SoapUI WebGUI

Betrieb der SoapUI WebGUI als Fat-JAR auf einer VM (Spezifikation, Abschnitt 5).

## 1. Voraussetzungen

- Linux-VM mit **JDK 21** (z. B. `apt install openjdk-21-jre-headless`)
- systemd
- Reverse-Proxy mit TLS (z. B. nginx, Caddy) für den App-Port
- Firewall-Zugriff auf den Mock-Portbereich (Default 18000–18999) für externe Consumer

## 2. Installation

```bash
# Dedizierter Benutzer mit minimalen Rechten — Groovy-Skripte in Projekten
# führen Code auf dem Server aus (NFA-02)!
useradd --system --home /var/lib/soapui-webgui --shell /usr/sbin/nologin soapui-web

mkdir -p /opt/soapui-webgui /etc/soapui-webgui /var/lib/soapui-webgui
chown soapui-web:soapui-web /var/lib/soapui-webgui

# Artefakt und Konfiguration
cp soapui-webgui-<version>.jar /opt/soapui-webgui/soapui-webgui.jar
cp deploy/application-example.yml /etc/soapui-webgui/application.yml   # anpassen!
cp deploy/soapui-webgui.service /etc/systemd/system/

systemctl daemon-reload
systemctl enable --now soapui-webgui
```

**Build des Artefakts:** `mvn package` (benötigt Zugriff auf den SmartBear-Nexus
`https://rapi.tools.ops.smartbear.io/nexus/content/groups/public/` — für reproduzierbare
Builds ist ein eigener Maven-Proxy/Mirror empfohlen, siehe Ist-Analyse).

## 3. Erststart

Beim ersten Start legt die Anwendung den Nutzer `admin` mit einem generierten
Initialpasswort an. Es steht im Journal:

```bash
journalctl -u soapui-webgui | grep Initialpasswort
```

Beim ersten Login erzwingt die Anwendung die Änderung des Passworts. Danach unter
**Nutzer** weitere Accounts anlegen (Rolle USER oder ADMIN); das jeweilige
Initialpasswort wird einmalig angezeigt.

## 4. Netzwerk & Firewall

| Port | Zweck | Empfehlung |
|---|---|---|
| 8080 (App) | Web-UI + API | **Nicht direkt freigeben.** Nur via Reverse-Proxy mit TLS (443) erreichbar machen; 8080 an `localhost` binden oder per Firewall auf den Proxy beschränken |
| 18000–18999 (Mocks) | MockService-Endpoints | Für externe Consumer freigeben. Die Ports kommen 1:1 aus den Projektdateien (`app.mock.port-min/max` begrenzt den erlaubten Bereich) |

Die Mock-Endpoints laufen bewusst **ohne Login** (externe Systeme testen dagegen);
die Web-UI ist vollständig authentifiziert.

Beispiel nginx:

```nginx
server {
    listen 443 ssl;
    server_name soapui.example.com;
    # ssl_certificate ...; ssl_certificate_key ...;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

## 5. Sicherheit

- **Groovy = Code-Ausführung:** SoapUI-Projekte können Groovy-Skripte enthalten
  (TestSteps, Setup/TearDown, Mock-Skripte, Dispatch). Jeder Nutzer mit Login kann
  damit Code unter dem Service-Benutzer ausführen. Deshalb:
  - nur vertrauenswürdigen Personen Accounts anlegen,
  - der Service läuft unter dediziertem Benutzer ohne Shell,
  - die systemd-Unit härtet das Dateisystem (`ProtectSystem=strict`, beschreibbar
    ist nur `/var/lib/soapui-webgui`).
- Passwörter liegen als bcrypt-Hashes in `users.json`; CSRF-Schutz ist aktiv.
- Actuator: `/actuator/health` ist offen (Monitoring), alle übrigen Endpunkte
  erfordern die ADMIN-Rolle.

## 6. Backup & Restore

Das gesamte Anwendungsdatenverzeichnis ist `app.data-dir`
(Default-Empfehlung `/var/lib/soapui-webgui`):

```
projects/<uuid>/project.xml   # Original-SoapUI-Projektdateien
projects/<uuid>/meta.json     # Metadaten inkl. Autostart-Flags
users.json                    # Nutzer (bcrypt)
engine/                       # SoapUI-Engine-Settings
```

**Backup = dieses Verzeichnis sichern.** Restore: Verzeichnis zurückspielen,
Service starten — Projekte und Autostart-Mocks kommen automatisch wieder.
Alle Schreibvorgänge sind atomar bzw. mit Backup-Datei abgesichert; ein bei einem
Absturz unterbrochener Speichervorgang wird beim Start automatisch aus
`project.xml.bak` wiederhergestellt.

## 7. Betrieb

- **Logs:** stdout → Journal: `journalctl -u soapui-webgui -f`
  (App- und Engine-Logs in einer Konfiguration; Groovy-Skript-Ausgaben erscheinen
  zusätzlich im Lauf-Log der UI)
- **Monitoring:** `GET /actuator/health` → `{"status":"UP"}`
- **Neustart/Updates:** JAR austauschen, `systemctl restart soapui-webgui`.
  Als Autostart markierte Mocks starten automatisch wieder (FA-14); ein einzelner
  fehlschlagender Mock (z. B. Port belegt) blockiert den Start nicht, sondern
  erscheint als Warnung auf der Projektliste und der Mock-Übersicht.
- **Edit-Sperren** sind in-memory: ein Neustart löst alle Sperren (dokumentiertes
  Verhalten, Spezifikation 2.1).

## 8. Grenzen (NFA-08)

Single-Instance-Architektur (die SoapUI-Engine hält JVM-weiten Zustand):
kein Cluster-Betrieb. Ausgelegt auf ~10 gleichzeitige Nutzer und ~50 gleichzeitig
laufende Mocks. Pro Projekt läuft maximal ein Testlauf gleichzeitig; Läufe
verschiedener Projekte laufen parallel.

## 9. Abnahme-Checkliste auf der Ziel-VM

- [ ] `systemctl status soapui-webgui` → active; `/actuator/health` → UP
- [ ] Login mit Initialpasswort aus dem Journal, Zwangsänderung greift
- [ ] Referenzprojekt hochladen, Mock starten, von einem **zweiten Rechner**
      per `curl http://<vm>:<mockport>/<pfad>` erreichen (Firewall-Nachweis)
- [ ] Mock als Autostart markieren, VM neu starten → Mock antwortet ohne Anmeldung
- [ ] Projekt herunterladen und im SoapUI-Desktop-Client öffnen (Roundtrip)
