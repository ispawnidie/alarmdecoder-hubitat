/**
 *  AlarmDecoder Network Appliance
 *
 *  Copyright 2016-2019 Nu Tech Software Solutions, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 *
 *  V4.0.0 - Hubitat only. Removed SmartThings tiles. Added SecurityKeypad,
 *    Initialize, connection health (networkStatus) and async HTTP requests.
 *    The device network id is now the AlarmDecoder MAC address so UPnP push
 *    notifications keep arriving after the appliance's IP address changes.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

// Number of zoneStatusN attributes. These hold the first N currently faulted
// zones for dashboards. All faulted zones are listed in "faultedZones".
@Field static final int MAX_ZONE_FAULT_TILES = 12

// Hubitat accepts unsolicited LAN traffic (UPnP NOTIFY) on this port.
@Field static final int HUB_LAN_PORT = 39501

// Mark the appliance offline if nothing has been heard from it for this long.
// SUBSCRIBE is renewed every 5 minutes and its reply counts as contact.
@Field static final long CONTACT_TIMEOUT_MS = 16 * 60 * 1000L

@Field static final int HTTP_TIMEOUT_SECONDS = 10

metadata {
  definition(
    name: "AlarmDecoder network appliance",
    namespace: "alarmdecoder",
    author: "Nu Tech Software Solutions, Inc.",
    importUrl: "https://raw.githubusercontent.com/ispawnidie/alarmdecoder-hubitat/master/drivers/alarmdecoder-network-appliance.groovy") {

    capability "Actuator"
    capability "Initialize"
    capability "Refresh"
    capability "SecurityKeypad"

    attribute(
      "panel_state",
      "enum",
      [
        "armed",
        "armed_exit",
        "armed_stay",
        "armed_stay_exit",
        "armed_night",
        "armed_night_exit",
        "disarmed",
        "alarming",
        "fire",
        "ready",
        "notready"
      ]
    )
    attribute "armed", "enum", ["armed", "disarmed"]
    attribute "alarmStatus", "enum", ["off", "stay", "away", "night"]
    attribute "networkStatus", "enum", ["online", "offline"]
    attribute "lastContact", "string"
    attribute "faultedZones", "string"
    attribute "panic_state", "string"
    attribute "fire_state", "string"
    attribute "aux_state", "string"
    attribute "zoneStatus1", "string"
    attribute "zoneStatus2", "string"
    attribute "zoneStatus3", "string"
    attribute "zoneStatus4", "string"
    attribute "zoneStatus5", "string"
    attribute "zoneStatus6", "string"
    attribute "zoneStatus7", "string"
    attribute "zoneStatus8", "string"
    attribute "zoneStatus9", "string"
    attribute "zoneStatus10", "string"
    attribute "zoneStatus11", "string"
    attribute "zoneStatus12", "string"

    command "arm_stay"
    command "arm_away"
    command "arm_night"
    command "arm_instant"
    command "exit"
    command "chime"
    command "fire"
    command "panic"
    command "aux"
    command "bypass", [[name: "zone*", type: "NUMBER", description: "Zone number to bypass"]]

    // Kept for compatibility with rules created for the old SmartThings
    // tiles. Prefer the commands above.
    command "fire1"
    command "fire2"
    command "panic1"
    command "panic2"
    command "aux1"
    command "aux2"
    command "bypassN", ["string"]
    command "bypass1"
    command "bypass2"
    command "bypass3"
    command "bypass4"
    command "bypass5"
    command "bypass6"
    command "bypass7"
    command "bypass8"
    command "bypass9"
    command "bypass10"
    command "bypass11"
    command "bypass12"
  }

  preferences {
    input(
      name: "api_key",
      type: "password",
      title: "API Key",
      description: "The key to access the AlarmDecoder webapp REST API",
      required: true)
    input(
      name: "user_code",
      type: "password",
      title: "Alarm Code",
      description: "The user code for the panel",
      required: true)
    input(
      name: "panel_type",
      type: "enum",
      title: "Panel Type",
      description: "Type of panel [ADEMCO,DSC]",
      options: ["ADEMCO", "DSC"],
      defaultValue: "ADEMCO",
      required: true)
  }
}

/*** Lifecycle ***/

def installed() {
  for (int i = 1; i <= MAX_ZONE_FAULT_TILES; i++)
    sendEvent(name: "zoneStatus${i}", value: "")
  sendEvent(name: "faultedZones", value: "")
  sendEvent(name: "panic_state", value: "default")
  sendEvent(name: "fire_state", value: "default")
  sendEvent(name: "aux_state", value: "default")
  sendEvent(name: "maxCodes", value: 0)
  sendEvent(name: "codeLength", value: 0)
  initialize()
}

def updated() {
  logTrace("updated")

  state.faulted_zones = []

  // Raw panel state values
  state.panel_ready = true
  state.panel_armed = false
  state.panel_armed_stay = false
  state.panel_armed_night = false
  state.panel_exit = false
  state.panel_fire_detected = false
  state.panel_alarming = false
  state.panel_panicked = false
  state.panel_on_battery = true
  state.panel_powered = true
  state.panel_chime = true
  state.panel_perimeter_only = false
  state.panel_entry_delay_off = false

  // Calculated alarm state ENUM
  state.panel_state = "disarmed"
  state.alarm_status = "off"
  state.armed = false

  state.panic_started = null
  state.fire_started = null
  state.aux_started = null

  for (int i = 1; i <= MAX_ZONE_FAULT_TILES; i++)
    sendEvent(name: "zoneStatus${i}", value: "")

  initialize()
}

/**
 * Called on install, on preference save, at hub startup (Initialize
 * capability) and by the service app after a relink or address change.
 */
def initialize() {
  logTrace("initialize: urn=${getDataValue('urn')}")
  unschedule()
  subscribeNotifications()
  runEvery5Minutes("subscribeNotifications")
  runIn(5, "refresh")
}

def uninstalled() {
  logTrace("uninstalled")
  unschedule()
}

/*** Incoming LAN messages ***/

/**
 * UPnP NOTIFY push messages from the AlarmDecoder webapp arrive here.
 * Hubitat routes them to this device because its network id is the
 * appliance's MAC address.
 */
def parse(String description) {
  def msg = parseLanMessage(description)
  String mac = msg?.mac?.toString()?.toUpperCase()
  String myMac = getDataValue("mac")?.toUpperCase()

  if (mac && myMac && mac != myMac) {
    logDebug("parse: skipping message from unexpected mac ${mac}")
    return []
  }

  markOnline()

  String body = msg?.body ?: ""
  String type = headerValue(msg?.headers, "Content-Type")
  logDebug("parse: type: ${type} headers: ${msg?.headers} body: ${body}")

  def events = []
  if (body) {
    if (type?.contains("json")) {
      events = parse_json(body)
    } else if (type?.contains("xml") || body.trim().startsWith("<")) {
      events = parse_xml(body)
    } else {
      logDebug("parse: unknown content type: ${type}")
    }
  }

  logDebug("parse: events: ${events}")
  return events
}

/*** Capabilities ***/

def refresh() {
  logTrace("refresh")

  String urn = getDataValue("urn")
  if (!urn || !_get_api_key()) {
    log.warn("${device.displayName}: refresh skipped, missing address or API key")
    return
  }

  try {
    asynchttpGet(
      "handleRefreshResponse",
      [
        uri: "http://${urn}",
        path: "/api/v1/alarmdecoder",
        query: [apikey: _get_api_key()],
        timeout: HTTP_TIMEOUT_SECONDS
      ]
    )
  } catch (e) {
    connectionFailed("refresh: ${e}")
  }
}

def handleRefreshResponse(resp, data) {
  if (resp.hasError()) {
    connectionFailed("refresh failed: status ${resp.status} ${resp.getErrorMessage()}")
    return
  }
  markOnline()
  parse_json(resp.data?.toString() ?: "").each { e -> sendEvent(e) }
}

/*** SecurityKeypad ***/

def armAway(delay = null) {
  arm_away()
}

def armHome(delay = null) {
  arm_stay()
}

def armNight(delay = null) {
  arm_night()
}

def setCode(codeposition, pincode, name = null) {
  log.warn("${device.displayName}: user codes are managed at the alarm panel")
}

def deleteCode(codeposition) {
  log.warn("${device.displayName}: user codes are managed at the alarm panel")
}

def getCodes() {
  log.warn("${device.displayName}: user codes are managed at the alarm panel")
}

def setCodeLength(pincodelength) {
  log.warn("${device.displayName}: code length is managed at the alarm panel")
}

def setEntryDelay(entranceDelay) {
  log.warn("${device.displayName}: entry delay is managed at the alarm panel")
}

def setExitDelay(exitDelay) {
  log.warn("${device.displayName}: exit delay is managed at the alarm panel")
}

/*** Commands ***/

def disarm() {
  logTrace("disarm")
  def user_code = _get_user_code()
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "${user_code}1"
  else if (settings.panel_type == "DSC")
    keys = "${user_code}"
  else
    log.warn("disarm: unknown panel_type.")

  send_keys(keys)
}

def exit() {
  logTrace("exit")
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "*"
  else if (settings.panel_type == "DSC")
    keys = "S8"
  else
    log.warn("exit: unknown panel_type.")

  send_keys(keys)
}

def arm_away() {
  logTrace("arm_away")
  def user_code = _get_user_code()
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "${user_code}2"
  else if (settings.panel_type == "DSC")
    keys = "<S5>"
  else
    log.warn("arm_away: unknown panel_type.")

  send_keys(keys)
}

def arm_stay() {
  logTrace("arm_stay")
  def user_code = _get_user_code()
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "${user_code}3"
  else if (settings.panel_type == "DSC")
    keys = "<S4>"
  else
    log.warn("arm_stay: unknown panel_type.")

  send_keys(keys)
}

def arm_night() {
  logTrace("arm_night")
  def user_code = _get_user_code()
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "${user_code}33"
  else if (settings.panel_type == "DSC")
    keys = "<S4>*1"
  else
    log.warn("arm_night: unknown panel_type.")

  send_keys(keys)
}

def arm_instant() {
  logTrace("arm_instant")
  def user_code = _get_user_code()
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "${user_code}7"
  else if (settings.panel_type == "DSC")
    keys = "<S4>*9"
  else
    log.warn("arm_instant: unknown panel_type.")

  send_keys(keys)
}

def fire() {
  logTrace("fire")
  state.fire_started = null
  send_keys("<S1>")
}

def panic() {
  logTrace("panic")
  state.panic_started = null
  send_keys("<S2>")
}

def aux() {
  logTrace("aux")
  state.aux_started = null
  send_keys("<S3>")
}

/*
 * Multi-step panic/fire/aux helpers from the SmartThings tiles. Each step
 * records a timestamp and the state resets to "default" after 10 seconds.
 */
def fire1() { startStage("fire") }
def fire2() { startStage("fire") }
def panic1() { startStage("panic") }
def panic2() { startStage("panic") }
def aux1() { startStage("aux") }
def aux2() { startStage("aux") }

private void startStage(String name) {
  String key = "${name}_started".toString()
  state[key] = now()
  runIn(10, "check${name.capitalize()}".toString())
  logTrace("${name} stage: ${state[key]}")
}

def checkFire() { clearStage("fire") }
def checkPanic() { clearStage("panic") }
def checkAux() { clearStage("aux") }

private void clearStage(String name) {
  def started = state["${name}_started".toString()]
  if (started != null && now() - (started as Long) >= 5000) {
    sendEvent(name: "${name}_state", value: "default", isStateChange: true)
    logTrace("clearing ${name}")
  }
}

def bypass1() { bypassN(1) }
def bypass2() { bypassN(2) }
def bypass3() { bypassN(3) }
def bypass4() { bypassN(4) }
def bypass5() { bypassN(5) }
def bypass6() { bypassN(6) }
def bypass7() { bypassN(7) }
def bypass8() { bypassN(8) }
def bypass9() { bypassN(9) }
def bypass10() { bypassN(10) }
def bypass11() { bypassN(11) }
def bypass12() { bypassN(12) }

/**
 * Bypass the zone currently shown in zoneStatusN.
 */
def bypassN(szValue) {
  def zone = device.currentValue("zoneStatus${szValue}")
  bypass(zone)
}

/**
 * Send a bypass command to the panel for a zone number.
 */
def bypass(zone) {
  logTrace("bypass ${zone}")

  if (zone == null || !zone.toString().isInteger() || !zone.toString().toInteger())
    return

  def user_code = _get_user_code()
  def zoneStr = zone.toString().toInteger().toString().padLeft(2, "0")
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "${user_code}6${zoneStr}*"
  else if (settings.panel_type == "DSC")
    keys = "*1${zoneStr}#"
  else
    log.warn("bypass: unknown panel_type.")

  send_keys(keys)
}

def chime() {
  logTrace("chime")
  def user_code = _get_user_code()
  def keys = ""

  if (settings.panel_type == "ADEMCO")
    keys = "${user_code}9*"
  else if (settings.panel_type == "DSC")
    keys = "<S6>"
  else
    log.warn("chime: unknown panel_type.")

  send_keys(keys)
}

/*** Business Logic ***/

/**
 * Process a panel state object from the xml/json parsers and return the
 * list of events to send.
 */
def update_state(data) {
  logTrace("update_state")
  def forceguiUpdate = false
  def skipstate = false
  def events = []

  /*
   * WARNING: we can get the Ready and Armed state events out of
   * order thanks to async io and the fact that both events occur
   * from a single AD2 message. Handle this gracefully to avoid client
   * update glitches.
   *
   * [10000001000100003A0-],008,[f702...],"****DISARMED****  Ready to Arm  "
   * [00100301000100003A0-],008,[f702...],"ARMED ***STAY***You may exit now"
   * [10000101000100003A0-],008,[f702...],"****DISARMED****  Ready to Arm  "
   */

  // Event Type 14 CID send raw data upstream
  if (data.eventid == 14) {
    events << createEvent(name: "cid-set", value: data.rawmessage, isStateChange: true)
    skipstate = true
  }

  // Event Type 17 RFX send eventmessage upstream
  if (data.eventid == 17) {
    events << createEvent(name: "rfx-set", value: data.eventmessage, isStateChange: true)
    skipstate = true
  }

  // Event Type 18 EXP/REL send eventmessage upstream
  if (data.eventid == 18) {
    events << createEvent(name: "exp-set", value: data.eventmessage, isStateChange: true)
    skipstate = true
  }

  // Event Type 19 AUI send eventmessage upstream
  if (data.eventid == 19) {
    events << createEvent(name: "aui-set", value: data.eventmessage, isStateChange: true)
    skipstate = true
  }

  // Event Type 5 Bypass
  if (data.eventid == 5) {
    events << createEvent(name: "bypass-set", value: (data.panel_bypassed ? "on" : "off"), isStateChange: true)
    skipstate = true
  }

  // Event Type 16 Chime
  if (data.eventid == 16) {
    events << createEvent(name: "chime-set", value: (data.panel_chime ? "on" : "off"), isStateChange: true)
    skipstate = true
  }

  // Do not trust panel state for the messages above, it could be out of sync.
  if (skipstate)
    return events

  def armed = data.panel_armed ||
    (data.panel_armed_stay != null && data.panel_armed_stay == true)

  def panel_state = (data.panel_ready ? "ready" : "notready")

  // Update our ready status virtual device
  if (forceguiUpdate || data.panel_ready != state.panel_ready)
    events << createEvent(name: "ready-set", value: (data.panel_ready ? "on" : "off"), isStateChange: true)

  // If armed update internal UI state to exit mode and armed state
  if (armed) {
    if (data.panel_exit) {
      if (data.panel_armed_stay) {
        panel_state = "armed_stay_exit"
        if (data.panel_entry_delay_off == false && data.panel_perimeter_only == true)
          panel_state = "armed_night_exit"
      } else {
        panel_state = "armed_exit"
      }
    } else {
      if (data.panel_armed_stay && data.panel_entry_delay_off == false && data.panel_perimeter_only == true)
        panel_state = "armed_night"
      else
        panel_state = (data.panel_armed_stay ? "armed_stay" : "armed")
    }
  }

  // Alarming overrides armed state.
  if (data.panel_alarming)
    panel_state = "alarming"

  // Fire overrides alarm since it's definitely more serious.
  if (data.panel_fire_detected)
    panel_state = "fire"

  // Update the Smoke Alarm virtual device.
  if (forceguiUpdate || data.panel_fire_detected != state.panel_fire_detected)
    events << createEvent(name: "smoke-set", value: (data.panel_fire_detected ? "detected" : "clear"), isStateChange: true)

  // If armed STAY changes
  if (forceguiUpdate || data.panel_armed_stay != state.panel_armed_stay) {
    if (data.panel_armed_stay) {
      def night = (data.panel_entry_delay_off == false && data.panel_perimeter_only)
      events << createEvent(name: "arm-stay-set", value: (night ? "off" : "on"), isStateChange: true)
      events << createEvent(name: "arm-away-set", value: "off", isStateChange: true)
      events << createEvent(name: "arm-night-set", value: (night ? "on" : "off"), isStateChange: true)
    }
  }

  // If the panel ARMED state changes
  if (forceguiUpdate || armed != state.armed) {
    if (!armed) {
      events << createEvent(name: "arm-away-set", value: "off", isStateChange: true)
      events << createEvent(name: "arm-stay-set", value: "off", isStateChange: true)
      events << createEvent(name: "arm-night-set", value: "off", isStateChange: true)
      events << createEvent(name: "disarm-set", value: "off", isStateChange: true)
    } else {
      if (!data.panel_armed_stay)
        events << createEvent(name: "arm-away-set", value: "on", isStateChange: true)
      events << createEvent(name: "disarm-set", value: "on", isStateChange: true)
    }
    events << createEvent(name: "armed", value: (armed ? "armed" : "disarmed"))
  }

  // Exit state
  if (forceguiUpdate || data.panel_exit != state.panel_exit)
    events << createEvent(name: "exit-set", value: (data.panel_exit ? "on" : "off"), isStateChange: true)

  // Perimeter only
  if (forceguiUpdate || data.panel_perimeter_only != state.panel_perimeter_only)
    events << createEvent(name: "perimeter-only-set", value: (data.panel_perimeter_only ? "on" : "off"), isStateChange: true)

  // Entry delay off
  if (forceguiUpdate || data.panel_entry_delay_off != state.panel_entry_delay_off)
    events << createEvent(name: "entry-delay-off-set", value: (data.panel_entry_delay_off ? "on" : "off"), isStateChange: true)

  // panel_state
  if (forceguiUpdate || panel_state != state.panel_state) {
    logTrace("update_state: new state **** ${panel_state} ****")
    events << createEvent(name: "panel_state", value: panel_state, isStateChange: true)
  }

  // alarm_status value ["off", "stay", "away", "night"]
  def alarm_status = "off"
  if (armed) {
    alarm_status = "away"
    if (data.panel_armed_stay == true) {
      alarm_status = "stay"
      if (!data.panel_entry_delay_off && data.panel_perimeter_only)
        alarm_status = "night"
    }
  }

  // Notify the service (HSM integration) and update the keypad state.
  if (forceguiUpdate || alarm_status != state.alarm_status) {
    events << createEvent(name: "alarmStatus", value: alarm_status, isStateChange: true)
  }
  def keypad = keypadState(alarm_status)
  if (device.currentValue("securityKeypad") != keypad)
    events << createEvent(name: "securityKeypad", value: keypad, descriptionText: "${device.displayName} is ${keypad}")

  // Alarm bell
  if (forceguiUpdate || data.panel_alarming != state.panel_alarming)
    events << createEvent(name: "alarmbell-set", value: (data.panel_alarming ? "on" : "off"), isStateChange: true)

  // Only adds events for zones that change state.
  events.addAll(build_zone_events(data))

  // Save state
  state.panel_state = panel_state
  state.armed = armed
  state.panel_ready = data.panel_ready
  state.panel_armed = data.panel_armed
  state.panel_armed_stay = data.panel_armed_stay
  state.panel_exit = data.panel_exit
  state.panel_fire_detected = data.panel_fire_detected
  state.panel_alarming = data.panel_alarming
  state.alarm_status = alarm_status
  state.panel_powered = data.panel_powered
  state.panel_on_battery = data.panel_on_battery
  state.panel_chime = data.panel_chime
  state.panel_perimeter_only = data.panel_perimeter_only
  state.panel_entry_delay_off = data.panel_entry_delay_off
  state.panel_armed_night = data.panel_armed_stay && data.panel_perimeter_only && !data.panel_entry_delay_off

  return events
}

private String keypadState(String alarm_status) {
  switch (alarm_status) {
    case "away": return "armed away"
    case "stay": return "armed home"
    case "night": return "armed night"
    default: return "disarmed"
  }
}

/*** Utility ***/

/**
 * Parse a JSON panel state from the REST API into events.
 */
def parse_json(String body) {
  def events = []
  if (!body)
    return events

  try {
    def result = new JsonSlurper().parseText(body)
    events = update_state(result)
    logDebug("parse_json in: ${result}")
    logDebug("parse_json out: ${events}")
  } catch (Exception e) {
    log.error("parse_json: Exception ${e}")
  }

  return events
}

/**
 * Parse an XML UPnP push message into events.
 */
def parse_xml(String body) {
  def events = []

  try {
    def xmlResult = new XmlSlurper().parseText(body)

    def resultMap = [:]
    resultMap['eventid'] = xmlResult.property.eventid.toInteger()
    resultMap['eventdesc'] = xmlResult.property.eventdesc.text()
    resultMap['eventmessage'] = xmlResult.property.eventmessage.text()
    resultMap['rawmessage'] = xmlResult.property.rawmessage.text()
    resultMap['last_message_received'] = xmlResult.property.panelstate.last_message_received.text()
    resultMap['panel_alarming'] = xmlResult.property.panelstate.panel_alarming.toBoolean()
    resultMap['panel_armed'] = xmlResult.property.panelstate.panel_armed.toBoolean()
    resultMap['panel_armed_stay'] = xmlResult.property.panelstate.panel_armed_stay.toBoolean()
    resultMap['panel_exit'] = xmlResult.property.panelstate.panel_exit.toBoolean()
    resultMap['panel_bypassed'] = xmlResult.property.panelstate.panel_bypassed.toBoolean()
    resultMap['panel_fire_detected'] = xmlResult.property.panelstate.panel_fire_detected.toBoolean()
    resultMap['panel_on_battery'] = xmlResult.property.panelstate.panel_on_battery.toBoolean()
    resultMap['panel_panicked'] = xmlResult.property.panelstate.panel_panicked.toBoolean()
    resultMap['panel_powered'] = xmlResult.property.panelstate.panel_powered.toBoolean()
    resultMap['panel_ready'] = xmlResult.property.panelstate.panel_ready.toBoolean()
    resultMap['panel_entry_delay_off'] = xmlResult.property.panelstate.panel_entry_delay_off.toBoolean()
    resultMap['panel_perimeter_only'] = xmlResult.property.panelstate.panel_perimeter_only.toBoolean()
    resultMap['panel_type'] = xmlResult.property.panelstate.panel_type.text()
    resultMap['panel_chime'] = xmlResult.property.panelstate.panel_chime.toBoolean()

    // Only update the zone list on zone change events:
    // <eventmessage><![CDATA[Zone <unnamed> (9) has been faulted.]]></eventmessage>
    if (xmlResult.property.eventmessage.text().startsWith('Zone ')) {
      def zones = []
      xmlResult.property.panelstate.panel_zones_faulted.z.each { e -> zones << e.toInteger() }
      resultMap['panel_zones_faulted'] = zones
    }

    def relays = []
    xmlResult.property.panelstate.panel_relay_status.r.each { e ->
      relays << ['address': e.a, 'channel': e.c, 'value': e.v]
    }
    resultMap['panel_relay_status'] = relays

    events = update_state(resultMap)

    logDebug("parse_xml in: ${resultMap}")
    logDebug("parse_xml out: ${events}")
  } catch (Exception e) {
    log.error("parse_xml: Exception ${e}")
  }

  return events
}

/**
 * Send a SUBSCRIBE request to the AlarmDecoder UPnP notification service so
 * it pushes panel events to the hub. Renewed every 5 minutes; also used as
 * a connection health check.
 */
def subscribeNotifications() {
  String urn = getDataValue("urn")
  logTrace("subscribeNotifications: ${urn}")

  checkContactTimeout()

  if (!urn || !_get_api_key()) {
    log.warn("${device.displayName}: not subscribing, missing address or API key")
    return
  }

  String hubAddress = "${location.hub.localIP}:${HUB_LAN_PORT}"

  def params = [
    method: "SUBSCRIBE",
    path: "/api/v1/alarmdecoder/event?apikey=${_get_api_key()}",
    headers: [
      HOST: urn,
      CALLBACK: "<http://${hubAddress}/notify>",
      NT: "upnp:event",
      TIMEOUT: "Second-28800"
    ]
  ]

  try {
    sendHubCommand(new hubitat.device.HubAction(params, device.deviceNetworkId, [callback: "handleSubscribeResponse"]))
  } catch (e) {
    connectionFailed("subscribe: ${e}")
  }
}

def handleSubscribeResponse(hubResponse) {
  def status = hubResponse?.status
  if (status != null && status >= 200 && status < 300) {
    logDebug("subscribe ok: ${hubResponse?.headers}")
    markOnline()
  } else {
    log.warn("${device.displayName}: SUBSCRIBE returned status ${status}")
  }
}

/**
 * Build zone events for zones that changed state, plus the zone status
 * attributes shown on dashboards.
 */
private def build_zone_events(data) {
  def events = []

  if (state.faulted_zones == null)
    state.faulted_zones = []

  def current_faults = data.panel_zones_faulted
  if (current_faults == null)
    return events

  def number_of_zones_faulted = current_faults.size()
  def new_faults = current_faults.minus(state.faulted_zones)
  def cleared_faults = state.faulted_zones.minus(current_faults)

  logTrace("Current faulted zones: ${current_faults}")
  logTrace("New faults: ${new_faults}")
  logTrace("Cleared faults: ${cleared_faults}")

  new_faults.each { zone ->
    events << createEvent(name: "zone-on", value: zone, isStateChange: true)
  }
  cleared_faults.each { zone ->
    events << createEvent(name: "zone-off", value: zone, isStateChange: true)
  }

  String faultList = current_faults.join(",")
  if (device.currentValue("faultedZones") != faultList)
    events << createEvent(name: "faultedZones", value: faultList)

  for (int i = 1; i <= MAX_ZONE_FAULT_TILES; i++) {
    def current = device.currentValue("zoneStatus${i}")?.toString() ?: ""
    def wanted = (i <= number_of_zones_faulted) ? current_faults[i - 1].toString() : ""
    if (current != wanted)
      events << createEvent(name: "zoneStatus${i}", value: wanted)
  }

  state.faulted_zones = current_faults

  return events
}

/**
 * POST keys to the AlarmDecoder REST API.
 */
def send_keys(String keys) {
  if (!keys) {
    log.warn("${device.displayName}: no keys to send")
    return
  }

  logTrace("send_keys: keys=${maskCode(keys)}")

  String urn = getDataValue("urn")
  if (!urn || !_get_api_key()) {
    log.warn("${device.displayName}: cannot send keys, missing address or API key")
    return
  }

  try {
    asynchttpPost(
      "handleSendKeysResponse",
      [
        uri: "http://${urn}",
        path: "/api/v1/alarmdecoder/send",
        query: [apikey: _get_api_key()],
        requestContentType: "application/json",
        body: [keys: keys],
        timeout: HTTP_TIMEOUT_SECONDS
      ],
      [keys: maskCode(keys)]
    )
  } catch (e) {
    connectionFailed("send_keys: ${e}")
  }
}

def handleSendKeysResponse(resp, data) {
  if (resp.hasError()) {
    log.error("${device.displayName}: failed to send keys ${data?.keys}: " +
      "status ${resp.status} ${resp.getErrorMessage()}")
    connectionFailed("send_keys failed")
    return
  }
  logDebug("send_keys ok: ${data?.keys}")
  markOnline()
}

/*** Connection health ***/

private void markOnline() {
  state.lastContact = now()
  sendEvent(name: "lastContact", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone ?: TimeZone.getDefault()))
  if (device.currentValue("networkStatus") != "online") {
    log.info("${device.displayName}: connected to ${getDataValue('urn')}")
    sendEvent(name: "networkStatus", value: "online")
  }
}

private void connectionFailed(String reason) {
  log.warn("${device.displayName}: ${reason}")
  if (device.currentValue("networkStatus") != "offline")
    sendEvent(name: "networkStatus", value: "offline", descriptionText: reason)
  // Ask the service to look for the appliance at a new address.
  parent?.rediscover()
}

private void checkContactTimeout() {
  def last = state.lastContact
  if (last != null && now() - (last as Long) > CONTACT_TIMEOUT_MS)
    connectionFailed("no contact from the AlarmDecoder for more than ${CONTACT_TIMEOUT_MS / 60000} minutes")
}

private String headerValue(headers, String name) {
  if (!(headers instanceof Map))
    return null
  def entry = headers.find { k, v -> k?.toString()?.equalsIgnoreCase(name) }
  return entry?.value?.toString()
}

private String maskCode(String keys) {
  def code = _get_user_code()?.toString()
  return (code && keys) ? keys.replace(code, "****") : keys
}

def _get_user_code() {
  return settings.user_code
}

def _get_api_key() {
  return settings.api_key
}

/**
 * Helper for parent access to state.
 */
def getStateValue(key) {
  return state[key]
}

private void logDebug(msg) {
  parent?.logDebug(msg)
}

private void logTrace(msg) {
  parent?.logTrace(msg)
}
