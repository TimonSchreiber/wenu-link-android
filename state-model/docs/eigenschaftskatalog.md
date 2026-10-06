# Eigenschaftskatalog

**Version 1.0**

Dieses Dokuments legt die Konsistenzeigenschaften fest, gegen die die Übersetzungslogik von WenuLink im Rahmen der Arbeit geprüft wird. Die Festschreibung erfolgt vor der systematischen Befundaufnahme. Änderungen werden über eine neue Versionsnummer, Datum, Begründung und einen Eintrag im Änderungsprotokoll dokumentiert.

---

## 1. Geltungsbereich

**Prüfgegenstand.** `AircraftStateMachine.sync(isArmed, isFlying)` im Modul `state-model`, einschließlich der davon ausgelösten Transitionen und der davon gelesenen Felder von `AircraftState`.

**Ausgangsfassung.** Commit `dcf6676`. Die im Zuge der Extraktion vorgenommenen Änderungen (Auflösung der Logger-Abhängigkeit, Injektion der `Clock`, Rückgabewert von `sync`) gehören zur Ausgangsfassung und sind keine Korrekturen.

**Zustandsraum.** Der Start- und Landezyklus mit den Zustandsfeldern `mavlink`, `landed`, `modeFlag`, `flightMode` und `armTimestamp`. Nicht Gegenstand sind Kamera- und Gimbalsteuerung, Missionsverwaltung, Parameterhandhabung, Home-Position sowie Failsafe- und Notfallzustände.

**Erreichbarkeit.** `S` bezeichnet die Menge der Zustände, die sich aus dem Zustand nach `BootTransition` und `StandbyTransition` durch eine endliche Folge zulässiger Ereignisse ergeben. Zulässige Ereignisse sind ein Abgleich `sync(isArmed, isFlying)`, ein Kommando, nachgebildet als `dispatch(τ)` unter der Bedingung `canDispatch(τ)`, oder ein Moduswechsel, nachgebildet als `updateFlightMode(m)` unter der Bedingung `isModeAllowed(m)`. Die Nachbildung orientiert sich am Aufrufmuster von `AircraftCommands` und `AircraftHandler`. Kommandos und Moduswechsel erweitern den für die Eigenschaften betrachteten Zustandsraum, sind selbst jedoch nicht Prüfgegenstand; die Eigenschaften prüfen ausschließlich das Verhalten von `sync`. Der Zustand vor dem Boot (`MAV_STATE_UNINIT` zusammen mit `MAV_LANDED_STATE_UNDEFINED`) liegt außerhalb des Geltungsbereichs.

**Nicht erfasst.** Die Zuordnung von Flugmodus auf `base_mode` und `custom_mode` setzt `FlightModeTransition`. Ob diese Zuordnung korrekt ist, prüft keine Eigenschaft dieses Katalogs.

---

## 2. Beobachtbare MAVLink-Sicht

Die Eigenschaften beziehen sich auf die beobachtbare MAVLink-Sicht der Bridge. Interne Hilfsprädikate und andere Implementierungsdetails sind nicht Teil dieser Sicht.

`view: S -> V` bildet einen Zustand auf die vier veröffentlichten Felder ab:

| Größe | MAVLink-Feld | Nachricht | Quelle in `AircraftState` |
|---|---|---|---|
| `systemStatus` | `system_status` | HEARTBEAT | `mavlink` |
| `baseMode` | `base_mode` | HEARTBEAT | `modeFlag` |
| `customMode` | `custom_mode` | HEARTBEAT | `flightMode.mode` |
| `landedState` | `landed_state` | EXTENDED_SYS_STATE | `landed` |

`safetyArmed` bezeichnet das Bit `MAV_MODE_FLAG_SAFETY_ARMED` in `baseMode` und ist eine Projektion der Sicht, kein eigenes Feld.

`π` bezeichnet die Projektion auf die telemetriebestimmten Anteile:

    π(v) = (v.systemStatus, v.safetyArmed, v.landedState)

---

## 3. Zielabbildung

`soll: T_stable -> π(V)` ordnet einer stabilen DJI-Telemetrie die MAVLink-Sicht zu, die ein ArduPilot-Copter im entsprechenden Betriebszustand veröffentlichen würde.

`T = {true, false} x {true, false}` ist die Menge der Telemetrieeingaben. `T_stable` ist die Teilmenge derjenigen Eingaben, die als Dauerzustand auftreten können; nach A-01 wird `T_stable = T` angenommen.

Die Zielabbildung ist eine Aussage über `π(V)`, nicht über `V`. `baseMode` jenseits des Arm-Bits und `customMode` hängen vom Flugmodus ab, und der Flugmodus ist kein Eingang von `sync`. Über sie trifft die Zielabbildung daher keine Aussage; ihr Verhalten regelt E-06.

| `isArmed` | `isFlying` | `systemStatus` | `safetyArmed` | `landedState` |
|---|---|---|---|---|
| false | false | `MAV_STATE_STANDBY` | false | `MAV_LANDED_STATE_ON_GROUND` |
| true  | false | `MAV_STATE_STANDBY` | true  | `MAV_LANDED_STATE_ON_GROUND` |
| true  | true  | `MAV_STATE_ACTIVE`  | true  | `MAV_LANDED_STATE_IN_AIR` |
| false | true  | `MAV_STATE_STANDBY` | false | `MAV_LANDED_STATE_ON_GROUND` |

**Herleitung.** `GCS_MAVLINK_Copter::vehicle_system_status()` meldet `MAV_STATE_CRITICAL` bei ausgelöstem Failsafe, `MAV_STATE_STANDBY` bei gesetztem `land_complete` und sonst `MAV_STATE_ACTIVE`. Der Arm-Zustand geht darin nicht ein. Da Failsafe außerhalb des Geltungsbereichs liegt, gilt `MAV_STATE_ACTIVE` genau dann, wenn sich das Luftfahrzeug in der Luft befindet. Ein Copter am Boden meldet `MAV_STATE_STANDBY`, auch wenn er scharf ist. Das Bit `MAV_MODE_FLAG_SAFETY_ARMED` folgt dagegen unmittelbar dem Arm-Zustand, weshalb Zeile 2 `STANDBY` mit gesetztem Arm-Bit verbindet. Diese Kombination ist zulässig.

**Zeile 4.** Beim Entschärfen setzt ArduCopter `land_complete` selbst (`init_disarm_motors` ruft `set_land_complete(true)`). Entschärft und in der Luft ist für einen Copter daher nicht beobachtbar; er meldet dieselbe Sicht wie am Boden. Dieselbe Semantik zeigt der Kommandopfad von WenuLink: `DisarmCommand` dispatcht `StandbyTransition`, deren `reduce` neben `mavlink` auch `landed` auf `ON_GROUND` setzt.

**Konsequenz.** Die Abbildung ist nicht injektiv. `(false, false)` und `(false, true)` liefern dieselbe Sicht; die Information, dass sich ein entschärftes Luftfahrzeug noch in der Luft befindet, wird verworfen. Das ist eine Entscheidung zugunsten der Protokolltreue und als Grenze des Verfahrens auszuweisen, nicht als Defekt.

---

## 4. Eigenschaften

Notation: `sync: S x T -> S`, `sync^n` die n-fache Anwendung mit derselben Telemetrie, `view` und `π` nach Abschnitt 2, `soll` nach Abschnitt 3.

### E-01 Widerspruchsfreiheit der veröffentlichten Felder

**Informell.** Die telemetriebestimmten MAVLink-Felder stehen in keinem erreichbaren Zustand untereinander im Widerspruch.

**Formal.** Für alle `s` in `S` gilt mit `(status, armed, landed) = π(view(s))`:

1. `status = MAV_STATE_ACTIVE` impliziert `landed = MAV_LANDED_STATE_IN_AIR`
2. `landed = MAV_LANDED_STATE_IN_AIR` impliziert `armed`

Aus 1 und 2 folgt, dass `MAV_STATE_ACTIVE` ein gesetztes Arm-Bit voraussetzt.

**Herkunft.** Spezifikationsgeleitet, aus der Kopplung von `system_status` und `landed_state` an `land_complete` in ArduCopter.

**Art.** Safety.

### E-02 Konvergenz (Selbststabilisierung)

**Informell.** Aus jedem erreichbaren Zustand erreicht die Bridge unter konstant anliegender Telemetrie innerhalb einer endlichen endlichen Anzahl von Abgleichschritten die für diese Telemetrie spezifizierte MAVLink-Sicht und verbleibt anschließend in dieser Sicht.

**Formal.** Für alle `s` in `S`, alle `t` in `T_stable` existiert ein `n` mit `0 <= n <= k`, sodass für alle `m >= n` gilt:

    π(view(sync^m(s, t))) = soll(t)

mit der Schranke `k = 3`.

**Reichweite der Quantifizierung.** `s` läuft über alle erreichbaren Zustände, unabhängig davon, ob die Folge, die zu `s` geführt hat, bereits in `t` endete. Damit erfasst E-02 auch den Fall, dass sich die anliegende Telemetrie zwischen zwei `sync`-Aufrufen geändert hat, etwa weil zwischen zwei Überwachungsschritten mehrere Zustandsänderungen aufgetreten sind.

**Schranke.** Die Schranke `k = 3` wird aus der längsten voresehenen Übergangsfolge des betarchteten Flugzustandsmodells abgeleitet:
Standby -> Arm -> Takeoff -> Flying umfasst drei Transitionen.

**Herkunft.** Theoriegeleitet über das Konzept der Selbststabilisierung nach Dijkstra, spezifikationsgeleitet über die Zielabbildung.

**Art.** Liveness.

### E-03 Verlaufsunabhängigkeit

**Informell.** Zustände, die in ihrer veröffentlichten MAVLink-Sicht übereinstimmen, verhalten sich unter derselben Telemetrie auch in der Folgesicht gleich. Interne Buchführung wirkt sich nicht auf das beobachtbare Verhalten aus.

**Formal.** Für alle `s1`, `s2` in `S` und alle `t` in `T`:

    view(s1) = view(s2)  =>  view(sync(s1, t)) = view(sync(s2, t))

**Herkunft.** Theoriegeleitet, aus der Beobachtungsäquivalenz.

**Art.** Safety.

### E-04a Idempotenz der beobachtbaren Sicht

**Informell.** Wiederholt gesendete identische Statusmeldungen verändern die veröffentlichte MAVLink-Sicht eines stabilen Zustands nicht.

**Formal.** Sei `s` in `S` stabil bezüglich `t` in `T_stable`, also `π(view(s)) = soll(t)`. Dann gilt für alle `n >= 1`:

1. `view(sync^n(s, t)) = view(s)`

**Herkunft.** Theoriegeleitet.

**Art.** Safety.

### E-04b Idempotenz auf Zustandsebene

**Informell.** Wiederholt gesendete identische Statusmeldungen verändern einen stabilen Zustand auch intern nicht.

**Formal.** Mit `s` und `t` wie in E-04a gilt für alle `n >= 1`:

1. `sync^n(s, t) = s`

**Verhältnis zu E-04a und E-03.** E-04b ist strikt stärker; es schließt neben der beobachtbaren Sicht auch interne Zustandsfelder wie  `armTimestamp` ein. Ein Verstoß gegen E-04b bei gültigem E-04a kann nur dann beobachtbar werden, wenn auch E-03 verletzt ist: Gilt E-03, so ist Sichtgleichheit eine Bisimulation und interne Zustandsunterschiede können sich unter den betrachteten Eingaben nicht auf die beobachtbare Sicht auswirken. Gegenbeispiele gegen E-04b sind daher nur zusammen mit dem Befund zu E-03 zu bewerten.

**Herkunft.** Theoriegeleitet.

**Art.** Safety, interne Fassung.

### E-05 Totalität

**Informell.** Keine Ereignisfolge führt zu einem undefinierten Zustand oder einer Ausnahme.

**Formal.** Für alle `s` in `S` und alle `t` in `T` terminiert `sync(s, t)` ohne Ausnahme, `view(sync(s, t))` enthält weder `MAV_STATE_UNINIT` noch `MAV_LANDED_STATE_UNDEFINED`.

**Herkunft.** Theoriegeleitet.

**Art.** Safety.

### E-06 Modeinvarianz des Abgleichs

**Informell.** Der Abgleich von Telemetrie verändert den gemeldeten Flugmodus nicht.

**Formal.** Für alle `s` in `S` und alle `t` in `T` gilt mit `v = view(s)` und `v' = view(sync(s, t))`:

1. `v'.customMode = v.customMode`
2. `v'.baseMode` ohne das Bit `SAFETY_ARMED` ist gleich `v.baseMode` ohne dieses Bit
3. das Bit `MAV_MODE_FLAG_CUSTOM_MODE_ENABLED` ist in `v'.baseMode` gesetzt

**Herkunft.** Theoriegeleitet, da `sync` ausschließlich die Synchronisation von Arm- und Flugzustand vornimmt und den Flugmodus nicht verändert. Die Konsistenz des `CUSTOM_MODE_ENABLED`-Bits ergibt sich aus der MAVLink-Spezifikation.

**Art.** Safety.

---

## 5. Offene Annahmen

### A-01 Auftretbarkeit von `(isArmed = false, isFlying = true)`

**Verifikationsstand.** Im HIL-Simulator der Mavic 2 ist die Kombination über keinen steuerbaren Pfad herstellbar. `turnOffMotors` wird im Flug sowohl über `DisarmCommand` als auch als direkter SDK-Aufruf mit der Meldung abgelehnt, dass das Luftfahrzeug fliege und die Motoren nicht abgeschaltet werden könnten. Die Stick-Kombination am Controller wird als reguläre Steuereingabe behandelt. In keinem aufgezeichneten Lauf trat die Kombination in der DJI-Telemetrie auf; Start und Landung verliefen ausnahmslos über `(true, false)`.

**Bewertung.** Aus der Nichtherstellbarkeit folgt nicht die Unmöglichkeit. Unbeobachtet bleiben nicht steuerbare Ereignisse wie Aufprall, Verkanten im Geäst oder Motorausfall. Die Messung belegt, dass DJI den befehlsgesteuerten Übergang verhindert, nicht dass der Zustand nicht auftreten kann. Eine Verifikation am realen Luftfahrzeug ist im Rahmen der Arbeit nicht möglich.

**Festlegung.** `T_stable = T` bleibt bestehen. Zeile 4 der Zielabbildung bleibt unverändert; ihre Herleitung stützt sich auf ArduCopter und ist von der Messung unabhängig. E-02 bleibt über ganz `T` quantifiziert. Für G-003 gilt die Auftretenswahrscheinlichkeit im befehlsgesteuerten Betrieb als gering, der Schweregrad bleibt unverändert, da die Auslösung außerhalb der Kontrolle der Bridge liegt.

---

## 6. Vor der Festschreibung bekannte Defekte

Die folgenden Defekte stammen aus dem Vorversuch zur Machbarkeit und sind vor dieser Festschreibung gefunden worden. Die Festschreibung regelt die anschließende systematische Befundaufnahme.

| ID | Kurzbeschreibung |
|---|---|
| G-001 | `ArmTransition` wird bei identischem Abgleich erneut dispatcht und überschreibt `armTimestamp` |
| G-002 | Der in `resolveFrom` mitgeführte `armTimestamp` verhindert die Konvergenz nach Disarm |
| G-003 | Keine `when`-Verzweigung deckt `isArmed = false, isFlying = true` ab, stilles Durchreichen an den `else`-Zweig |
| G-004 | Verdrehter Zeitvergleich im Timeout-Zweig, die Arm-Rückfallregel greift nie |

---

## 7. Änderungsprotokoll

Änderungen erfolgen nie stillschweigend. Jede Änderung erhält eine neue Versionsnummer, einen eigenen Commit, einen Eintrag in dieser Tabelle mit Datum und Begründung sowie einen eigenen Tag. Die vorherige Version bleibt über ihren Tag abrufbar.

| Version | Datum | Änderung |
|---|---|---|
| 1.0 | `06.10.2026` | Erstfassung. Eigenschaften E-01 bis E-06, Zielabbildung nach Abschnitt 3, offene Annahme A-01. |
