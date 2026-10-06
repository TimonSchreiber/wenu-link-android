# Messprotokoll: Auftretbarkeit von (isArmed = false, isFlying = true)

**Version 1.0, 23.09.2026**

Referenziert aus A-01 des Eigenschaftskatalogs.

---

## 1. Fragestellung

Kann die Telemetriekombination `(isArmed = false, isFlying = true)` am realen DJI-System auftreten, und welche Folge von `(areMotorsOn, isFlying)` liefert die MSDK-Telemetrie, wenn die Motoren im Flug abgeschaltet werden?

Die Frage entscheidet über Zeile 4 der Zielabbildung, den Geltungsbereich von E-02 und die Einstufung von G-003.

## 2. Abgrenzung

Prüfgegenstand ist das Verhalten des DJI-Systems, nicht `AircraftStateMachine.sync`.
Beobachtungen zum Verhalten von WenuLink werden als Nebenbefunde in Abschnitt 7 geführt und sind nicht Gegenstand der Fragestellung.

## 3. Versuchsaufbau

### 3.1 Hardware

| Komponente | Bezeichnung | Version |
|---|---|---|
| Luftfahrzeug | DJI Mavic 2 Pro | Firmware <Version> |
| Fernsteuerung | RC1B | Firmware <Version> |
| Mobilgerät | <Modell> | Android <Version> |

Propeller demontiert. Flugmodusschalter durchgehend auf P.

### 3.2 Software

| Komponente | Version |
|---|---|
| WenuLink | Branch `experiment/disarm-in-flight`, Commit `bf09beb`, abgeleitet von `develop` `5d67f08` |
| DJI MSDK | v4 |
| QGroundControl | 5.0.8 |

### 3.3 Simulation

Verwendet wird der in das MSDK integrierte Simulator, gestartet über `SimManager` (`flightController.simulator.start`). Die Simulation läuft auf dem Flight Controller des Luftfahrzeugs; DJI Assistant 2 wurde nicht eingesetzt.

### 3.4 Instrumentierung

Abweichungen vom Ausgangsstand `develop`, ausschließlich zu Messzwecken:

| Änderung | Zweck |
|---|---|
| `DisarmProbe`: Protokollierung von `FlightControllerState`, `SimulatorState`, Ein- und Ausgabe von `sync` sowie Ereignissen | Aufzeichnung |
| `FCManager.armMotors` und `disarmMotors`: Übergabe des Completion-Callbacks korrigiert (runde statt geschweifte Klammern) | Ohne diese Korrektur bleibt die Antwort des SDK unbeobachtbar |
| Parallele Registrierung des `FlightControllerState`-Callbacks im Simulationsbetrieb | Vergleich beider Telemetriequellen |
| `DisarmTrigger`: Auslösung per `adb`-Broadcast, wahlweise über `DisarmCommand` oder als direkter SDK-Aufruf | Auslösung unabhängig von der Bodenstation, siehe 4.1 |

Die Änderungen sind nicht in `develop` übernommen worden.

## 4. Durchführung

### 4.1 Auslösung

Die Auslösung über QGroundControl war nicht praktikabel: Die Bestätigungsslider verschwanden fortlaufend, weil die Anzeige zwischen scharf und fliegend wechselte. Ursache ist der Widerspruch zwischen `MAV_STATE_ACTIVE` mit gesetztem Arm-Bit im HEARTBEAT und `MAV_LANDED_STATE_ON_GROUND` in EXTENDED_SYS_STATE, den WenuLink im Flug veröffentlicht. Der Zustand von WenuLink selbst war stabil (siehe 7.<n>). Ausgelöst wurde daher per Broadcast.

### 4.2 Läufe

| Lauf | Auslösung | Wiederholungen |
|---|---|---|
| Baseline | keine, Start und Landung ohne Eingriff | <n> |
| A | `DisarmCommand` über den regulären Kommandopfad | <n> |
| B | direkter Aufruf von `turnOffMotors`, Umgehung der Zustandsprüfung | <n> |
| C | Stick-Kombination am Controller (beide Sticks unten, unten-innen, unten-außen) | <n> |

Ablauf je Lauf: App neu gestartet, Logpuffer geleert, Motoren per Stick-Kombination gestartet, Steigflug auf <Höhe>, Schwebeflug, Auslösung, Aufzeichnung für weitere <Dauer>.

### 4.3 Aufzeichnung

`adb logcat -v epoch`, vollständiger Puffer, Auswertung über den Tag `DisarmProbe`.
Dateien und Prüfsummen in Abschnitt 9.

## 5. Ergebnisse

### 5.1 Reaktion auf den Abschaltbefehl

In allen Läufen A und B erreichte der Befehl das SDK und wurde abgelehnt.
Antwortzeit 5 bis 9 ms.

| Lauf | Pfad | Antwort des SDK |
|---|---|---|
| A1 | `DisarmCommand` | Ablehnung, Begründung: Luftfahrzeug fliegt, Motoren nicht abschaltbar |
| B1 | direkt | gleiche Ablehnung |
| <...> | | |

In Lauf A meldete `DisarmCommand` nach Ablauf des Timeouts von 5 s `Unable to disarm motors`.

Die Telemetrie blieb unverändert: `motors=true flying=true`, Höhe konstant (A1: 25,4 m; B1: 19,6 m), `flightMode=GPS_ATTI`.

### 5.2 Reaktion auf die Stick-Kombination

Alle erprobten Kombinationen wurden als reguläre Steuereingabe verarbeitet. Es folgten Sinkflug und Landung; eine Notabschaltung trat nicht auf.

### 5.3 Beobachtete Übergangsfolgen

Start (C1, Werte aus `FlightControllerState`):

| Zeit relativ | motors | flying | flightMode |
|---|---|---|---|
| 0,0 s | true | false | MOTORS_JUST_STARTED |
| 1,2 s | true | false | ASSISTED_TAKEOFF |
| 8,0 s | true | true | GPS_ATTI |

Landung (C1):

| Zeit relativ | motors | flying | flightMode |
|---|---|---|---|
| 0,0 s | true | true | CONFIRM_LANDING |
| 5,3 s | true | false | CONFIRM_LANDING |
| 5,4 s | false | false | GPS_ATTI |

Start und Landung verlaufen über `(true, false)`. Der Zustand `isFlying = true` trat ausschließlich bei laufenden Motoren auf.

### 5.4 Nichtauftreten der untersuchten Kombination

In <Gesamtzahl> ausgewerteten Telemetrieproben aus <n> Läufen trat `(areMotorsOn = false, isFlying = true)` in keiner Probe auf, weder in `FlightControllerState` noch in `SimulatorState`.

## 6. Schlussfolgerung

Im HIL-Simulator der Mavic 2 ist die Kombination über keinen steuerbaren Pfad herstellbar. DJI verhindert die Abschaltung im Flug auf SDK-Ebene und behandelt die Stick-Kombination als Steuereingabe.

Aus der Nichtherstellbarkeit folgt nicht die Unmöglichkeit. Nicht steuerbare Ereignisse wie Aufprall, Verkanten im Geäst oder Motorausfall bleiben unbeobachtet. Die Festlegung für den Eigenschaftskatalog steht in A-01.

## 7. Nebenbefunde

Außerhalb der Fragestellung, jeweils mit eigenem Issue:

| Nr. | Befund | Issue |
|---|---|---|
| 1 | `FCManager` übergibt den Completion-Callback in vier Methoden so, dass `onResult` nie aufgerufen wird | <#> |
| 2 | `SimManager.state2Telemetry` übernimmt `positionZ` unverändert als `relativeAltitude`; im Simulationsbetrieb wird die Höhe negativ gemeldet (FC 19,6 m gegenüber SIM −19,685) | <#> |
| 3 | Nach Abbruch der Telemetriequelle arbeitet `sync` mit dem zuletzt empfangenen Wert weiter und meldet weiterhin `IN_AIR`; protokolliert wird lediglich `Unexpected telemetry stop!` | <#> |
| 4 | `SimulatorState` meldet bei der Landung `flying=false` rund 2,5 s früher als `FlightControllerState` | <#> |

Beobachtungen zu bereits bekannten Defekten, als Bestätigung am Gerät:

| Defekt | Beobachtung |
|---|---|
| G-001 | `ArmTransition` wird im Flug alle 200 ms erneut dispatcht, `armTimestamp` jedes Mal überschrieben |
| G-002 | Nach der Landung `in=(false,false)`, veröffentlicht bleibt `MAV_STATE_ACTIVE` mit gesetztem Arm-Bit bis zum Neustart der Anwendung |
| <ID> | `landed` bleibt während des gesamten Flugs auf `ON_GROUND`; `TakeoffTransition` wird über den Abgleichpfad nie ausgelöst |

## 8. Gültigkeit und Grenzen

1. Simulierte Flugphysik; das Verhalten des realen Luftfahrzeugs kann abweichen, insbesondere bei Aufprall- und Ausfallerkennung.
2. Im Simulationsbetrieb liest WenuLink `SimulatorState`. Die parallele Aufzeichnung von `FlightControllerState` zeigt bei Start und Ablehnung Übereinstimmung, bei der Landung eine Abweichung von 2,5 s.
3. Stichprobenumfang <n> Läufe je Variante, ein Luftfahrzeug, eine Firmwareversion.
4. In Lauf B1 endete die Aufzeichnung 48 s nach der Auslösung durch einen USB-Suspend des Mobilgeräts. Die Messfrage war zu diesem Zeitpunkt bereits beantwortet. In den Wiederholungen wurde das Ereignis ausgeschlossen (Kontrolle auf `FUNCTIONFS_SUSPEND` im Log).

## 9. Artefakte

| Datei | Lauf | SHA-256 |
|---|---|---|
| `baseline1.log` | Baseline | <hash> |
| `A1.log` | A | <hash> |
| <...> | | |

Branch `experiment/disarm-in-flight`, Commit `<hash>`. Die Logs liegen unter `<Pfad im Repository oder Anhang>`.

## 10. Änderungsprotokoll

| Version | Datum | Änderung |
|---|---|---|
| 1.0 | <Datum> | Erstfassung. |
