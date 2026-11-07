/**
 *  Virtual Carbon Monoxide Detector for alarm panel zones
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
 *  Events for this device are sent by the AlarmDecoder service app. The app
 *  routes panel zone events using the "Zone Number" preference, and 5800 RF
 *  events using the "Serial Number" and loop preferences.
 */

metadata {
  definition(
    name: "AlarmDecoder virtual carbon monoxide detector",
    namespace: "alarmdecoder",
    author: "Nu Tech Software Solutions, Inc.",
    importUrl: "https://raw.githubusercontent.com/ispawnidie/alarmdecoder-hubitat/master/drivers/alarmdecoder-virtual-carbon-monoxide-detector.groovy") {
    capability "CarbonMonoxideDetector"
    capability "Sensor"
    capability "Tamper Alert"
    attribute "low_battery", "enum", ["true", "false"]
    attribute "last_checkin", "number"
  }

  preferences {
    input(
      name: "invert",
      type: "bool",
      title: "Invert signal",
      description: "Invert signal. Changes ON/OFF, OPEN/CLOSE, DETECTED/CLEAR.",
      required: false)
    input(
      name: "zone",
      type: "number",
      title: "Zone Number",
      description: "Panel zone # required for zone events.",
      required: false)
    input(
      name: "serial",
      type: "string",
      title: "Serial Number",
      submitOnChange: true,
      description: "The serial number of a 5800 RF device (optional).",
      required: false)
    if (serial) {
      input(
        name: "zoneLoop",
        type: "number",
        title: "Zone Loop",
        range: "1..4",
        description: "The loop to use for zone open/close.",
        required: true)
      input(
        name: "tamperLoop",
        type: "number",
        title: "Tamper Loop",
        range: "1..4",
        description: "The loop to use to detect tamper.",
        defaultValue: 4,
        required: false)
    }
  }
}

/**
 * The parent app cannot read device preferences directly, so mirror them
 * into device data values. Unset preferences remove the data value so the
 * app never sees the string "null".
 */
def installed() {
  syncDataValues()
}

def updated() {
  syncDataValues()
}

private void syncDataValues() {
  mirrorDataValue("invert", invert)
  mirrorDataValue("zone", zone)
  mirrorDataValue("serial", serial)
  mirrorDataValue("zoneLoop", serial ? zoneLoop : null)
  mirrorDataValue("tamperLoop", serial ? tamperLoop : null)
}

private void mirrorDataValue(String name, value) {
  if (value == null || value.toString().trim() == "") {
    removeDataValue(name)
  } else {
    updateDataValue(name, value.toString().trim())
  }
}

