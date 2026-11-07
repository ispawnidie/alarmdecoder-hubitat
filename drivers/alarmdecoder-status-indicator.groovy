/**
 *  Virtual Status Indicator for alarm panel
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
 *
 *  Contact sensor that mirrors a panel state (Ready, Armed, Bypass, ...).
 *  Events are sent by the AlarmDecoder service app.
 */

metadata {
  definition(
    name: "AlarmDecoder status indicator",
    namespace: "alarmdecoder",
    author: "Nu Tech Software Solutions, Inc.",
    importUrl: "https://raw.githubusercontent.com/ispawnidie/alarmdecoder-hubitat/master/drivers/alarmdecoder-status-indicator.groovy") {
    capability "Contact Sensor"
    capability "Sensor"
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
      description: "Zone # required for zone events.",
      required: false)
  }
}

/**
 * The parent app cannot read device preferences directly, so mirror them
 * into device data values.
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
}

private void mirrorDataValue(String name, value) {
  if (value == null || value.toString().trim() == "") {
    removeDataValue(name)
  } else {
    updateDataValue(name, value.toString().trim())
  }
}
