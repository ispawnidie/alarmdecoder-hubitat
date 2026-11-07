/**
 *  AlarmDecoder Service Manager
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
 * V1.0.0 - Scott Petersen - Initial design and release 2015/12/10 - 2017/04/20
 * V2.0.0 - Sean Mathews <coder@f34r.com> - 2018/05/21
 *    Changed to use UPNP Push API in AD2 web app.
 * V2.0.1 - Sean Mathews <coder@f34r.com> - 2018/05/21
 *    Adding CID device management support.
 * V2.0.2 - Sean Mathews <coder@f34r.com> - 2019/01/02
 *    Fixed app 20 second max timeout. AddZone is now async, added more zones.
 * V2.0.3 - Sean Mathews <coder@f34r.com> - 2019/01/11
 *    Improved/fixed issues with previous app 20 timeout after more testing.
 * V2.0.4 - Sean Mathews <coder@f34r.com> - 2019/02/19
 *    Support multiple instances of service by changing unique ID message
 *    filters by MAC.
 * V2.0.5 - Sean Mathews <coder@f34r.com> - 2019/03/06
 *    Add switch to create Disarm button.
 * V2.0.6 - Sean Mathews <coder@f34r.com> - 2019/03/28
 *    Compatibility between ST and HT. Still requires some manual code edits but
 *    it will be minimal.
 * V2.0.7 - Sean Mathews <coder@f34r.com> - 2019/05/05
 *    Add RFX virtual device management to track Ademco 5800 wireless or VPLEX
 *    sensors directly.
 * V2.0.8 - Sean Mathews <coder@f34r.com> - 2019/05/17
 *    Added Exit button. New flag from AD2 state to detect exit state.
 *    Added rebuild devices button.
 * V2.0.9 - Sean Mathews <coder@f34r.com> - 2019/05/17
 *    Split some devices from a single combined Momentary to a Momentary and a
 *    Contact for easy access by other systems.
 * V2.1.0 - Sean Mathews <coder@f34r.com> - 2019/08/02
 *    Modified virtual RFX devices to allow inverting signal and capabilities
 *    detection. Set the RFX device type to AlarmDecoder virtual smoke and it
 *    will report as a smoke detector. Modified sending of events creating a
 *    wrapper to invert and adjust to the device type.
 * V2.1.1 - Sean Mathews <coder@f34r.com> - 2019/09/21
 *    Add screen to re-link a local AlarmDecoder WEBAPP if the IP/MAC address
 *    change. Refactoring cleanup localization. Refactoring long lines.
 * V2.2.0 - Sean Mathews <coder@f34r.com> - 2019/09/20 - 2019/09/30
 *    A major re-factor and cleanup of the code. Improved UI. Ability to
 *    reconnect the AlarmDecoder if the IP or MAC change using the App UI.
 *    Refactor how zones are mapped to devices. Now each virtual device can
 *    be assigned a zone number in preferences.
 * V4.0.0 - 2026/11/07
 *    Hubitat only. Removed all SmartThings code paths.
 *    Device network ids now use the AlarmDecoder MAC address instead of its
 *    IP address. Existing devices are renamed in place on first run, so
 *    rules and dashboards keep working.
 *    Fixed relink changing the port to the IP address, and Done/Rebuild
 *    creating a second set of devices after a relink.
 *    Rebuild now only creates missing devices and keeps existing ones.
 *    The app follows the appliance to a new IP address automatically using
 *    periodic SSDP discovery.
 *    HSM integration handles both arming and armed states.
 *
 */

import groovy.transform.Field

@Field static final String APPNAMESPACE = "alarmdecoder"
@Field static final String SSDPTERM = "urn:schemas-upnp-org:device:AlarmDecoder:1"
@Field static final String APPLIANCE_TYPE = "AlarmDecoder network appliance"

// Bump when the device network id scheme changes. See migrateDeviceIds().
@Field static final int SCHEMA_VERSION = 2

// Minimum time between SSDP rediscovery requests from the driver.
@Field static final long REDISCOVER_INTERVAL_MS = 5 * 60 * 1000L

// Forget SSDP discoveries not seen for this long.
@Field static final long DISCOVERY_EXPIRE_MS = 5 * 60 * 1000L

/**
 * Device label name settings
 * For easy use with Echo you can set the '''sname''' to something easy to
 * say such as 'Security' then you can say 'Alexa - Security Arm Stay On'
 */
@Field static final String lname = "AlarmDecoder"
@Field static final String sname = "Security"
@Field static final String guiname = "AlarmDecoder UI"

/**
 * Zone sensor types. Zone sensors share one index (DNI <key>:switchN).
 * The setting holds the number of sensors of each type to create.
 */
@Field static final Map ZONE_TYPES = [
  contact: [driver: "AlarmDecoder virtual contact sensor", setting: "inputContactSensorCount"],
  co: [driver: "AlarmDecoder virtual carbon monoxide detector", setting: "inputCODetector"],
  motion: [driver: "AlarmDecoder virtual motion detector", setting: "inputMotionDetector"],
  shock: [driver: "AlarmDecoder virtual shock sensor", setting: "inputShockSensor"],
  smoke: [driver: "AlarmDecoder virtual smoke alarm", setting: "inputSmokeDetector"]
]

/**
 * Map of capability -> [attribute, alerting value, normal value] used to
 * translate on/off into the event a virtual device understands.
 */
@Field static final Map CAPABILITY_EVENTS = [
  "Switch": ["switch", "on", "off"],
  "Contact Sensor": ["contact", "open", "closed"],
  "Motion Sensor": ["motion", "active", "inactive"],
  "Shock Sensor": ["shock", "detected", "clear"],
  "Carbon Monoxide Detector": ["carbonMonoxide", "detected", "clear"],
  "Smoke Detector": ["smoke", "detected", "clear"]
]

/**
 * CID table
 * List of some of the CID #'s and descriptions.
 * 000 will trigger a manual input of the CID number.
 */
@Field static final Map cid_numbers = ["0": "000 - Other / Custom",
  "10?": "100-102 - ALL Medical alarms",
  "11?": "110-118 - ALL Fire alarms",
  "12?": "120-126 - ALL Panic alarms",
  "13?": "130-139 - ALL Burglar alarms",
  "14?": "140-149 - ALL General alarms",
  "1[5-6]?": "150-169 - ALL 24 HOUR AUX alarms",
  "154": "154 - Water Leakage",
  "158": "158 - High Temp",
  "162": "162 - Carbon Monoxide Detected",
  "301": "301 - AC Loss",
  "3??": "3?? - All System Troubles",
  "401": "401 - Arm AWAY OPEN/CLOSE",
  "441": "441 - Arm STAY OPEN/CLOSE",
  "4[0,4]1": "4[0,4]1 - Arm Stay or Away OPEN/CLOSE"
]

/**
 * Service definition and preferences
 */
definition(
  name: "AlarmDecoder service",
  namespace: "alarmdecoder",
  author: "Nu Tech Software Solutions, Inc.",
  description: "AlarmDecoder (Service Manager)",
  category: "Safety & Security",
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/ispawnidie/alarmdecoder-hubitat/master/apps/alarmdecoder-service.groovy",
  singleInstance: true)

preferences {
  page(name: "page_main")
  page(name: "page_discover")
  page(name: "page_remove_all")
  page(name: "page_rebuild_all")
  page(name: "page_cid_management")
  page(name: "page_add_new_cid")
  page(name: "page_add_new_cid_confirm")
  page(name: "page_remove_selected_cid")
  page(name: "page_rfx_management")
  page(name: "page_add_new_rfx")
  page(name: "page_add_new_rfx_confirm")
  page(name: "page_remove_selected_rfx")
  page(name: "page_relink_update")
  page(name: "page_select_device")
}

/*
 * Localization strings
 */
def getText(String name, Object... args) {
  def en_strings = [

    // misc or used multiple places
    "home": "home",
    "home_screen": "Return to the main page.",
    "done_note": "Press the \"Done\" button on this page to install the service devices.",
    "input_selected_devices_title": "Select device (%s found).",
    "section_monitor_integration": "Monitor Integration",
    "section_zone_sensor_settings": "Zone Sensors",
    "tap_here": "[ * TAP HERE * ]",

    //  Main Page
    "page_main_title": "Setup And Management",
    "page_main_device_found": "${lname} service found.\nSelect from management options below.",
    "page_main_status": "Linked appliance: %s (MAC %s), status: %s",
    "debug_logging": "Enable debug logging (turns off after 30 minutes)",
    "trace_logging": "Enable trace logging (turns off after 30 minutes)",

    // Discover/Install
    "page_discover_title": "Install Service",
    "page_discover_desc": "Tap to discover and install your ${lname} Appliance.",
    "page_discover_section_selected_device": "Selected device info: %s",
    "section_sensor_counts": "Sensor Configuration",
    "contact_sensor_count": "How many contact (window/door) sensors should be created?",
    "smoke_detector_count": "How many smoke detectors should be created?",
    "co_detector_count": "How many carbon monoxide detectors should be created?",
    "motion_sensor_count": "How many motion sensors should be created?",
    "shock_sensor_count": "How many shock (glass break) sensors should be created?",
    "create_disarm_switch": "Create a switch that can be used to disarm the alarm (note this can be a security risk if this is exposed to things like Google Home or Alexa)",

    // Service Settings
    "monIntegrationHSM": "Integrate with Hubitat Safety Monitor?",
    "monChangeStatusHSM": "Automatically change Hubitat Safety Monitor status when armed or disarmed?",
    "defaultSensorToClosed": "Default zone sensors to closed?",

    // CID Management
    "page_cid_management_title": "Contact ID Device Management",
    "page_cid_management_desc": "Tap to manage virtual devices.",
    "input_cid_devices_title": "Remove installed CID virtual devices.",
    "input_cid_devices_desc": "Tap to select.",

    //// Add new CID
    "page_add_new_cid_title": "Add New Contact ID Virtual Switch",
    "page_add_new_cid_desc": "Tap to add new CID switch",
    "section_build_cid": "CODE mask: %s",
    "input_cid_number_title": "Select the CID number for this device",
    "input_cid_number_desc": "Tap to select",
    "input_cid_number_raw_title": "Enter raw Contact ID CODE or simple regex pattern",
    "section_cid_value": "USER# or ZONE# mask : %s",
    "input_cid_value_title": "Zero padded 3 digit User# or Zone# or simple regex pattern ex. '001' or '???'",
    "section_cid_partition": "Partition mask: %s",
    "input_cid_partition_title": "Enter the partition. Use 0 for system and ? for any.",
    "section_cid_name": "Device Name",
    "input_cid_name_title": "Enter the new device name or blank for auto",
    "section_cid_label": "Device Label",
    "input_cid_label_title": "Enter the new device label or blank for auto",

    ////// Add new confirm
    "page_add_new_cid_confirm_title": "Add new CID switch : %s",
    "add_new_cid_confirm_info": "Attempted to add new CID device. If it fails review the log.",
    "href_add_new_cid_confirm_desc": "Tap to confirm and add",

    ////// Remove CID
    "page_remove_selected_cid_title": "Remove selected virtual CID devices",
    "page_remove_selected_cid_desc": "Tap to remove selected virtual CID device",
    "page_remove_selected_cid_info": "Attempted to remove selected devices. This may fail if the device is in use. If it fails review the log.",

    // RFX Management
    "page_rfx_management_title": "RFX Device Management",
    "page_rfx_management_desc": "Tap to manage virtual devices.",
    "input_rfx_devices_title": "Selected RFX devices to remove.",
    "input_rfx_devices_desc": "Tap to select.",
    "page_remove_selected_rfx_title": "Remove selected RFX devices.",
    "href_remove_selected_rfx_desc": "Tap to remove the selected RFX devices.",
    "info_page_remove_selected_rfx": "Attempted to remove selected devices. This may fail if the device is in use. If it fails review the log.",
    "page_add_new_rfx_title": "Add New RFX Virtual Device",
    "page_add_new_rfx_desc": "To add new RFX virtual device\n%s",
    "info_add_new_rfx_confirm": "Attempted to add new RFX device. If it fails review the log.",

    "section_build_rfx": "Build Device Name :",
    "section_rfx_names": "Optional name and label for the new device.",
    "input_rfx_name": "Enter the new device name or blank for auto.",
    "input_rfx_label": "Enter the new device label or blank for auto.",
    "input_rfx_sn": "Enter Serial # or simple REGEX pattern.",
    "input_rfx_supv": "Supervision bit: Enter 1 to watch or ? to ignore.",
    "input_rfx_bat": "Battery: Enter 1 to monitor or ? to ignore.",
    "input_rfx_loop0": "Loop 0: Enter 1 to monitor or ? to ignore.",
    "input_rfx_loop1": "Loop 1: Enter 1 to monitor or ? to ignore.",
    "input_rfx_loop2": "Loop 2: Enter 1 to monitor or ? to ignore.",
    "input_rfx_loop3": "Loop 3: Enter 1 to monitor or ? to ignore.",

    // Select Device discovery
    "page_select_device_title": "Select ${lname} Appliance",
    "page_select_device_desc": "Select the local ${lname} Appliance this service will connect to. Be sure the WEBAPP UPNP notification service is enabled.",

    // Update AD2 IP/MAC relink
    "info_confirm_relink_update": "Link the ${lname} service to the selected device at (%s).",
    "page_relink_update_title": "Update Service Settings",
    "page_relink_update_desc": "Update settings or re-link the ${lname} Appliance. IP address changes are normally detected automatically.\nTap to select.",
    "page_relink_section_active_device": "Linked device info: %s",
    "page_relink_section_selected_device": "Selected device info: %s",
    "info_relink_update_done": "Re-link finished. Review the results below.",

    // Rebuild All
    "page_rebuild_all_title": "Rebuild Virtual Devices",
    "page_rebuild_all_desc": "Find and re-create missing virtual devices. Existing devices are kept.\nTap to select.",
    "confirm_rebuild_all": "Tap to confirm and rebuild all.",
    "href_rebuild_devices": "Creates any missing devices using the counts below.",
    "info_rebuild_all_done": "Rebuild done. Monitor the logs for any errors.",
    "info_rebuild_all_confirm": "This will create any missing child devices. Existing devices are not changed. Monitor the logs for any errors.",
    "info_rebuild_no_appliance": "No ${lname} appliance device was found. Use Install Service to set it up.",

    // Remove All
    "page_remove_all_title": "Uninstall ${lname} Service",
    "page_remove_all_desc": "This page is for removing and uninstalling the ${lname} service.\nTap to select.",
    "remove_all_href_confirm": "[ * TAP HERE * ]",
    "info_remove_all_done": "Removed all child devices.",
    "info_remove_all_confirm": "This will attempt to remove all child devices. This may fail if the device is in use. If it fails review the log and manually remove the usage.\nTap to confirm.",

    // End
    "": ""
  ]
  if (args) {
    try {
      return String.format(en_strings[name], args)
    } catch (Exception ex) {
      log.error("getText: bad format for '${name}'")
      return "**err:${name}"
    }
  } else {
    return en_strings[name]
  }
}

/*** Pages/UI ***/

/**
 * Misc helper sections
 */
def section_save_note() {
  section(getText("done_note")) {}
}

def section_no_save_note() {
  // Hide the "Previous" button on sub pages; the browser back button is used.
  section { paragraph "<script>\$('button[name=\"_action_previous\"]').hide()</script>" }
}

def section_home() {
  section(getText("home")) {
    href(
      name: "href_home",
      title: getText("tap_here"),
      required: false,
      description: getText("home_screen"),
      page: "page_main"
    )
  }
}

/**
 * The main service page
 */
def page_main() {

  // Make sure older installs use the current device id scheme.
  migrateDeviceIds()

  def appliance = getApplianceDevice()

  dynamicPage(name: "page_main", title: getText("page_main_title"), install: true, uninstall: false) {
    if (!appliance) {
      // Not installed show discovery page to complete the install.
      section("") {
        href(
          name: "href_discover",
          title: getText("page_discover_title"),
          required: false,
          description: getText("page_discover_desc"),
          page: "page_discover"
        )
      }
    } else {
      section(getText("page_main_device_found")) {
        paragraph getText("page_main_status",
          appliance.getDataValue("urn") ?: "?",
          appliance.getDataValue("mac") ?: "?",
          appliance.currentValue("networkStatus") ?: "unknown")
        href(
          name: "href_cid_management",
          title: getText("page_cid_management_title"),
          required: false,
          description: getText("page_cid_management_desc"),
          page: "page_cid_management"
        )
      }
      section("") {
        href(
          name: "href_rfx_management",
          title: getText("page_rfx_management_title"),
          required: false,
          description: getText("page_rfx_management_desc"),
          page: "page_rfx_management"
        )
      }
      section("") {
        href(
          name: "href_relink_update",
          title: getText("page_relink_update_title"),
          required: false,
          description: getText("page_relink_update_desc"),
          page: "page_relink_update"
        )
      }
      section("") {
        href(
          name: "href_rebuild_all",
          title: getText("page_rebuild_all_title"),
          required: false,
          description: getText("page_rebuild_all_desc"),
          page: "page_rebuild_all"
        )
      }
      section("") {
        href(
          name: "href_remove_all",
          title: getText("page_remove_all_title"),
          required: false,
          description: getText("page_remove_all_desc"),
          page: "page_remove_all"
        )
      }
      section("") {
        input("debugOutput", "bool", title: getText("debug_logging"), submitOnChange: true)
        input("traceOutput", "bool", title: getText("trace_logging"), submitOnChange: true)
      }
    }
  }
}

/**
 * Page page_cid_management generator.
 */
def page_cid_management() {
  return dynamicPage(
    name: "page_cid_management",
    title: getText("page_cid_management_title")
  ) {
    def found_devices = getChildDevices().findAll {
      it.deviceNetworkId.contains(":CID-")
    }.collect { getDeviceNamePart(it) }

    section_no_save_note()
    section {
      if (found_devices.size()) {
        input(
          name: "input_cid_devices",
          type: "enum",
          required: false,
          multiple: true,
          options: found_devices,
          title: getText("input_cid_devices_title"),
          description: getText("input_cid_devices_desc"),
          submitOnChange: true
        )
        if (input_cid_devices) {
          href(
            name: "href_remove_selected_cid",
            required: false,
            page: "page_remove_selected_cid",
            title: getText("page_remove_selected_cid_title"),
            description: getText("page_remove_selected_cid_desc")
          )
        }
      }
    }
    section {
      href(
        name: "href_add_new_cid",
        required: false,
        page: "page_add_new_cid",
        title: getText("page_add_new_cid_title"),
        description: getText("page_add_new_cid_desc")
      )
    }
    section_home()
  }
}

/**
 * Page page_remove_selected_cid generator
 */
def page_remove_selected_cid() {
  def errors = removeSelectedDevices(":CID-", input_cid_devices)
  app.removeSetting("input_cid_devices")

  return dynamicPage(
    name: "page_remove_selected_cid",
    title: getText("page_remove_selected_cid_title")
  ) {
    section_no_save_note()
    section {
      paragraph getText("page_remove_selected_cid_info")
      errors.each { error -> paragraph(error) }
    }
    section_home()
  }
}

/**
 * Delete child devices whose name part is in the selected list.
 */
private List removeSelectedDevices(String marker, selected) {
  def results = []
  if (!selected)
    return results
  if (selected instanceof String)
    selected = [selected]

  getChildDevices().each { device ->
    if (device.deviceNetworkId.contains(marker)) {
      def device_name = getDeviceNamePart(device)
      if (selected.contains(device_name)) {
        logTrace("removing device ${device.deviceNetworkId}")
        try {
          deleteChildDevice(device.deviceNetworkId)
          results << "Success removing " + device_name
        } catch (e) {
          log.error("There was an error (${e}) when trying to delete the child device ${device_name}")
          results << "Error removing " + device_name
        }
      }
    }
  }
  return results
}

/**
 * Page page_add_new_cid generator
 */
def page_add_new_cid() {
  return dynamicPage(
    name: "page_add_new_cid",
    title: getText("page_add_new_cid_title")
  ) {
    section_no_save_note()
    // show pre defined CID number templates to select from
    section(getText("section_build_cid", buildcid())) {
      input(
        name: "input_cid_number",
        type: "enum",
        required: true,
        multiple: false,
        options: cid_numbers,
        title: getText("input_cid_number_title"),
        description: getText("input_cid_number_desc"),
        submitOnChange: true
      )
    }
    // if a CID entry is selected then check the value if it is "0"
    // to show raw input section
    if (input_cid_number) {
      if (input_cid_number == "0") {
        section {
          input(
            name: "input_cid_number_raw",
            type: "text",
            required: true,
            title: getText("input_cid_number_raw_title"),
            defaultValue: 110,
            submitOnChange: true
          )
        }
      }
      section(getText("section_cid_value", buildcidvalue())) {
        input(
          name: "input_cid_value",
          type: "text",
          required: true,
          title: getText("input_cid_value_title"),
          defaultValue: "???",
          submitOnChange: true
        )
      }
      section(getText("section_cid_partition", input_cid_partition)) {
        input(
          name: "input_cid_partition",
          type: "enum",
          required: true,
          defaultValue: 1,
          options: ['?', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'],
          submitOnChange: true,
          title: getText("input_cid_partition_title")
        )
      }
      section(getText("section_cid_name")) {
        input(
          name: "input_cid_name",
          type: "text",
          required: false,
          defaultValue: '',
          submitOnChange: true,
          title: getText("input_cid_name_title")
        )
      }
      section(getText("section_cid_label")) {
        input(
          name: "input_cid_label",
          type: "text",
          required: false,
          defaultValue: '',
          submitOnChange: true,
          title: getText("input_cid_label_title")
        )
      }
      // If input_cid_number or input_cid_number_raw have a value
      if ((input_cid_number && (input_cid_number != "0")) ||
        (input_cid_number_raw)) {
        section("") {
          href(
            name: "href_add_new_cid_confirm",
            required: false,
            page: "page_add_new_cid_confirm",
            title: getText("page_add_new_cid_confirm_title",
              buildcidlabel() + "(" + buildcidnetworkid() + ")"),
            description: getText("href_add_new_cid_confirm_desc")
          )
        }
      }
    }
    section_home()
  }
}

/**
 * page_add_new_cid helpers
 */
def buildcid() {
  return (input_cid_number == "0") ? input_cid_number_raw : input_cid_number
}

def buildcidname() {
  return input_cid_name ? "CID-" + input_cid_name : buildcidnetworkid()
}

def buildcidlabel() {
  return input_cid_label ? "CID-" + input_cid_label : buildcidnetworkid()
}

def buildcidnetworkid() {
  return "CID-${buildcid()}-${input_cid_partition}-${buildcidvalue()}"
}

def buildcidvalue() {
  return input_cid_value
}

/**
 * Page page_add_new_cid_confirm generator.
 */
def page_add_new_cid_confirm() {
  def errors = []
  def newcidlabel = buildcidlabel()
  def newcidname = buildcidname()
  def newcidnetworkid = buildcidnetworkid()

  // Add virtual CID switch if it does not exist.
  if (createChild("AlarmDecoder action button indicator", newcidnetworkid,
      "${sname} ${newcidlabel}", [name: "switch", value: "off"],
      "${getDeviceKey()}:${newcidname}")) {
    errors << "Success adding ${newcidlabel}"
  } else {
    errors << "Error adding ${newcidlabel}: exists or failed, see the log"
  }

  return dynamicPage(
    name: "page_add_new_cid_confirm",
    title: newcidlabel
  ) {
    section_no_save_note()
    section(getText("add_new_cid_confirm_info")) {
      errors.each { error -> paragraph(error) }
    }
    section_home()
  }
}

/**
 * Page page_rfx_management generator.
 */
def page_rfx_management() {
  return dynamicPage(
    name: "page_rfx_management",
    title: getText("page_rfx_management_title")
  ) {
    def found_devices = getChildDevices().findAll {
      it.deviceNetworkId.contains(":RFX-")
    }.collect { getDeviceNamePart(it) }

    section_no_save_note()
    section("") {
      if (found_devices.size()) {
        input(
          name: "input_rfx_devices",
          type: "enum",
          required: false,
          multiple: true,
          options: found_devices,
          title: getText("input_rfx_devices_title"),
          description: getText("input_rfx_devices_desc"),
          submitOnChange: true
        )
        if (input_rfx_devices) {
          href(
            name: "href_remove_selected_rfx",
            required: false,
            page: "page_remove_selected_rfx",
            title: getText("page_remove_selected_rfx_title"),
            description: getText("href_remove_selected_rfx_desc")
          )
        }
      }
    }
    section("") {
      href(
        name: "href_add_new_rfx",
        required: false,
        page: "page_add_new_rfx",
        title: getText("tap_here"),
        description: getText("page_add_new_rfx_desc", "")
      )
    }
    section_home()
  }
}

/**
 * Page page_remove_selected_rfx generator
 */
def page_remove_selected_rfx() {
  def errors = removeSelectedDevices(":RFX-", input_rfx_devices)
  app.removeSetting("input_rfx_devices")

  return dynamicPage(
    name: "page_remove_selected_rfx",
    title: getText("page_remove_selected_rfx_title")
  ) {
    section_no_save_note()
    section(getText("info_page_remove_selected_rfx")) {
      errors.each { error -> paragraph(error) }
    }
    section_home()
  }
}

/**
 * Page page_add_new_rfx generator
 */
def page_add_new_rfx() {
  return dynamicPage(
    name: "page_add_new_rfx",
    title: getText("page_add_new_rfx_title")
  ) {
    section_no_save_note()
    section(getText("section_build_rfx")) {
      paragraph getText("section_rfx_names")
      input(
        name: "input_rfx_label",
        type: "text",
        required: false,
        defaultValue: '',
        submitOnChange: true,
        title: getText("input_rfx_label")
      )
      input(
        name: "input_rfx_name",
        type: "text",
        required: false,
        defaultValue: '',
        submitOnChange: true,
        title: getText("input_rfx_name")
      )
    }
    section {
      input(name: "input_rfx_sn", type: "text", required: true, defaultValue: '000000',
        submitOnChange: true, title: getText("input_rfx_sn"))
      input(name: "input_rfx_bat", type: "text", required: true, defaultValue: '?',
        submitOnChange: true, title: getText("input_rfx_bat"))
      input(name: "input_rfx_supv", type: "text", required: true, defaultValue: '?',
        submitOnChange: true, title: getText("input_rfx_supv"))
      input(name: "input_rfx_loop0", type: "text", required: true, defaultValue: '1',
        submitOnChange: true, title: getText("input_rfx_loop0"))
      input(name: "input_rfx_loop1", type: "text", required: true, defaultValue: '?',
        submitOnChange: true, title: getText("input_rfx_loop1"))
      input(name: "input_rfx_loop2", type: "text", required: true, defaultValue: '?',
        submitOnChange: true, title: getText("input_rfx_loop2"))
      input(name: "input_rfx_loop3", type: "text", required: true, defaultValue: '?',
        submitOnChange: true, title: getText("input_rfx_loop3"))
    }
    section {
      href(
        name: "href_add_new_rfx_confirm",
        required: false,
        page: "page_add_new_rfx_confirm",
        title: getText("tap_here"),
        description: getText("page_add_new_rfx_desc",
          "${buildrfxlabel()} (${buildrfxnetworkid()})")
      )
    }
    section_home()
  }
}

/**
 * page_add_new_rfx helpers
 */
def buildrfx() {
  return input_rfx_sn
}

def buildrfxname() {
  return input_rfx_name ? "RFX-" + input_rfx_name : buildrfxnetworkid()
}

def buildrfxlabel() {
  return input_rfx_label ? "RFX-" + input_rfx_label : buildrfxnetworkid()
}

def buildrfxnetworkid() {
  return "RFX-${buildrfx()}-${buildrfxvalue()}"
}

def buildrfxvalue() {
  return "${input_rfx_bat}-${input_rfx_supv}-${input_rfx_loop0}-" +
    "${input_rfx_loop1}-${input_rfx_loop2}-${input_rfx_loop3}"
}

/**
 * Page page_add_new_rfx_confirm generator.
 */
def page_add_new_rfx_confirm() {
  def errors = []
  def newrfxlabel = buildrfxlabel()
  def newrfxname = buildrfxname()
  def newrfxnetworkid = buildrfxnetworkid()

  if (createChild("AlarmDecoder action button indicator", newrfxnetworkid,
      "${sname} ${newrfxlabel}", [name: "switch", value: "off"],
      "${getDeviceKey()}:${newrfxname}")) {
    errors << "Success adding ${newrfxlabel}"
  } else {
    errors << "Error adding ${newrfxlabel}: exists or failed, see the log"
  }

  return dynamicPage(
    name: "page_add_new_rfx_confirm",
    title: newrfxlabel
  ) {
    section_no_save_note()
    section("") {
      paragraph getText("info_add_new_rfx_confirm")
      errors.each { error -> paragraph(error) }
    }
    section_home()
  }
}

/**
 * Sensor count inputs shared by the install and rebuild pages.
 */
def section_sensor_counts() {
  section(getText("section_sensor_counts")) {
    input(name: "inputContactSensorCount", type: "number", defaultValue: 20, required: true,
      submitOnChange: true, title: getText("contact_sensor_count"))
    input(name: "inputSmokeDetector", type: "number", defaultValue: 2, required: true,
      submitOnChange: true, title: getText("smoke_detector_count"))
    input(name: "inputCODetector", type: "number", defaultValue: 2, required: true,
      submitOnChange: true, title: getText("co_detector_count"))
    input(name: "inputMotionDetector", type: "number", defaultValue: 2, required: true,
      submitOnChange: true, title: getText("motion_sensor_count"))
    input(name: "inputShockSensor", type: "number", defaultValue: 1, required: true,
      submitOnChange: true, title: getText("shock_sensor_count"))
    input(name: "inputCreateDisarm", type: "bool", defaultValue: true,
      submitOnChange: true, title: getText("create_disarm_switch"))
  }
}

/**
 * Page page_rebuild_all generator.
 */
def page_rebuild_all(params) {
  def message = ""
  def results = []

  if (params?.confirm) {
    if (getApplianceDevice()) {
      results = createVirtualDevices()
      configureDeviceSubscriptions()
      message = getText("info_rebuild_all_done")
    } else {
      message = getText("info_rebuild_no_appliance")
    }
  }

  return dynamicPage(
    name: "page_rebuild_all",
    title: getText("page_rebuild_all_title")
  ) {
    section_no_save_note()
    if (!params?.confirm) {
      section("") {
        href(
          name: "href_confirm_rebuild_all_devices",
          title: getText("confirm_rebuild_all"),
          description: getText("href_rebuild_devices"),
          required: false,
          page: "page_rebuild_all",
          params: [confirm: true]
        )
      }
      section_sensor_counts()
      message = getText("info_rebuild_all_confirm")
    }
    section("") {
      paragraph message
      results.each { paragraph it }
    }
    section_home()
  }
}

/**
 * Page page_remove_all generator.
 */
def page_remove_all(params) {
  def message = ""

  return dynamicPage(
    name: "page_remove_all",
    title: getText("page_remove_all_title")
  ) {
    section_no_save_note()
    if (params?.confirm) {
      removeAllChildDevices()
      message = getText("info_remove_all_done")
    } else {
      section("") {
        href(
          name: "href_confirm_remove_all_devices",
          title: getText("remove_all_href_confirm"),
          description: getText("info_remove_all_confirm"),
          required: false,
          page: "page_remove_all",
          params: [confirm: true]
        )
      }
    }
    section("") {
      paragraph message
    }
    section_home()
  }
}

/**
 * Page page_select_device generator
 *   reloaded every N seconds to refresh list
 */
def page_select_device() {
  // send out UPNP discovery messages and watch for responses
  discover_alarmdecoder()

  def found_devices = discoveredDeviceOptions()
  def numFound = found_devices.size() ?: 0

  // When already installed this page is used to re-link the appliance.
  def nextPage = getApplianceDevice() ? "page_relink_update" : "page_discover"

  return dynamicPage(
    name: "page_select_device",
    title: getText("page_select_device_title"),
    install: false,
    nextPage: nextPage,
    refreshInterval: 5
  ) {
    section("Discovered devices: (scanning)") {
      input(
        name: "input_selected_devices",
        type: "enum",
        required: false,
        title: getText("input_selected_devices_title", numFound),
        multiple: false,
        submitOnChange: true,
        options: found_devices
      )
    }
  }
}

/**
 * Build the [ "IPHEX:PORTHEX": "AlarmDecoder @ ip" ] options map.
 */
private Map discoveredDeviceOptions() {
  def found_devices = [:]
  getDevices().each { k, v ->
    logDebug("discovered: ${v}")
    if (v.ip && v.port) {
      found_devices["${v.ip}:${v.port}"] =
        "AlarmDecoder @ ${convertHexToIP(v.ip)}:${convertHexToInt(v.port)} (MAC ${v.mac})"
    }
  }
  return found_devices
}

/**
 * Return the discovered device for the "IPHEX:PORTHEX" selection.
 */
private Map getSelectedDiscovery(selected = input_selected_devices) {
  if (!selected)
    return null
  return getDevices().find { k, v -> "${v.ip}:${v.port}" == "${selected}" }?.value
}

/**
 * Page page_discover generator.
 */
def page_discover() {

  // send out UPNP discovery messages and watch for responses
  discover_alarmdecoder()

  return dynamicPage(
    name: "page_discover",
    title: getText("page_discover_title"),
    install: true,
    uninstall: false
  ) {
    def section_select_device_heading = ""

    if (input_selected_devices) {
      def d = getSelectedDiscovery()
      section_select_device_heading =
        getText("page_discover_section_selected_device",
          "\nurn: ${getHostAddressFromDNI(input_selected_devices)}\n" +
          "ssdpPath: ${d?.ssdpPath}\nmac: ${d?.mac}\nusn: ${d?.ssdpUSN}")
      section_save_note()
    }
    section(section_select_device_heading) {
      href(
        name: "href_confirm_discover_update",
        title: getText("tap_here"),
        description: getText("page_select_device_desc"),
        required: false,
        page: "page_select_device"
      )
    }
    if (input_selected_devices) {
      section_sensor_counts()
      section_monitor_settings()
    }
  }
}

/**
 * HSM and zone default settings shared by the install and relink pages.
 */
def section_monitor_settings() {
  section(getText("section_monitor_integration")) {
    input(
      name: "monIntegration",
      type: "bool",
      defaultValue: true,
      submitOnChange: true,
      title: getText("monIntegrationHSM")
    )
    input(
      name: "monChangeStatus",
      type: "bool",
      defaultValue: true,
      submitOnChange: true,
      title: getText("monChangeStatusHSM")
    )
  }
  section(getText("section_zone_sensor_settings")) {
    input(
      name: "defaultSensorToClosed",
      type: "bool",
      defaultValue: true,
      submitOnChange: true,
      title: getText("defaultSensorToClosed")
    )
  }
}

/**
 * Page page_relink_update generator.
 */
def page_relink_update(params) {
  def message = ""
  def results = []

  if (params?.confirm) {
    message = getText("info_relink_update_done")
    results = relinkAppliance(params?.selected)
  }

  return dynamicPage(
    name: "page_relink_update",
    title: getText("page_relink_update_title")
  ) {
    section_no_save_note()

    if (!params?.confirm) {
      def d = getApplianceDevice()
      if (!d) {
        section("") {
          paragraph getText("info_rebuild_no_appliance")
        }
      } else {
        section(getText("page_relink_section_active_device",
          "\ndni: ${d.deviceNetworkId}\nurn: ${d.getDataValue('urn')}\n" +
          "ssdpPath: ${d.getDataValue('ssdpPath')}\nmac: ${d.getDataValue('mac')}\n" +
          "usn: ${d.getDataValue('ssdpUSN')}\nstatus: ${d.currentValue('networkStatus')}")) {
          href(
            name: "href_select_device_relink_update",
            title: getText("tap_here"),
            description: getText("page_select_device_desc"),
            required: false,
            page: "page_select_device"
          )
        }

        def sel = getSelectedDiscovery()
        if (sel) {
          def urn = getHostAddressFromDNI(input_selected_devices)
          section(getText("page_relink_section_selected_device",
            "\nurn: ${urn}\nssdpPath: ${sel.ssdpPath}\nmac: ${sel.mac}\nusn: ${sel.ssdpUSN}")) {
            href(
              name: "href_confirm_relink_update",
              title: getText("tap_here"),
              description: getText("info_confirm_relink_update", "${urn}"),
              required: false,
              page: "page_relink_update",
              params: [confirm: true, selected: input_selected_devices]
            )
          }
        }
      }
      section_monitor_settings()
    }
    section("") {
      paragraph message
      results.each { paragraph(it) }
    }
    section_home()
  }
}

/*** Standard service callbacks ***/

def installed() {
  logTrace("installed")
  logDebug("Installed with settings: ${settings}")
  initialize()
}

def updated() {
  logTrace("updated")
  logDebug("Updated with settings: ${settings}")
  initialize()
}

def uninstalled() {
  logTrace("uninstalled")
  unschedule()
  removeAllChildDevices()
}

/**
 * initialize called upon install/update.
 *   Add subscriptions and schedules.
 *   Devices are only created on first install, never on a later "Done".
 */
def initialize() {
  logTrace("initialize")

  unsubscribe()
  unschedule()

  migrateDeviceIds()

  state.lastMONStatus = null
  state.lastAlarmDecoderStatus = null

  // First install: create the appliance and virtual devices.
  if (!getApplianceDevice() && input_selected_devices) {
    addExistingDevices()
  }

  initSubscriptions()
  configureDeviceSubscriptions()

  // Watch for the appliance moving to a new IP address.
  runEvery30Minutes("discover_alarmdecoder")

  if (settings.debugOutput || settings.traceOutput)
    runIn(1800, "logsOff")

  // Renew the push subscription and refresh state.
  getApplianceDevice()?.initialize()
}

def logsOff() {
  log.info("AlarmDecoder service: debug/trace logging disabled")
  app.updateSetting("debugOutput", [value: "false", type: "bool"])
  app.updateSetting("traceOutput", [value: "false", type: "bool"])
}

private void removeAllChildDevices() {
  getChildDevices().each {
    try {
      logDebug("deleting child device: ${it.deviceNetworkId}")
      deleteChildDevice(it.deviceNetworkId)
    } catch (Exception e) {
      log.error("exception while deleting ${it.deviceNetworkId}: ${e}")
    }
  }
  state.remove("deviceKey")
}

/*** Device id migration ***/

/**
 * Older versions used the appliance's hex IP address as the device network
 * id (DNI). The DNI is now the appliance MAC address so Hubitat keeps
 * routing UPnP push messages to the appliance after an IP address change.
 * This renames existing devices in place; their device ids, and so any
 * rules or dashboards that use them, do not change.
 */
private void migrateDeviceIds() {
  if ((state.schemaVersion ?: 1) >= SCHEMA_VERSION)
    return

  def appliance = getApplianceDevice()
  if (!appliance) {
    // Nothing installed yet.
    state.schemaVersion = SCHEMA_VERSION
    return
  }

  String mac = normalizeMac(appliance.getDataValue("mac"))
  if (!mac) {
    log.warn("migrateDeviceIds: appliance ${appliance.displayName} has no MAC address; " +
      "use the re-link page to select it again.")
    return
  }

  String oldKey = appliance.deviceNetworkId
  log.info("migrateDeviceIds: moving device ids from ${oldKey} to ${mac}")
  rekeyDevices(appliance, oldKey, mac)

  appliance.updateDataValue("mac", mac)
  state.deviceKey = mac
  state.remove("ip")
  state.remove("port")
  state.remove("hubId")
  state.schemaVersion = SCHEMA_VERSION
}

/**
 * Rename the appliance to newKey and every child "oldKey:suffix" to
 * "newKey:suffix". Returns a list of result messages.
 */
private List rekeyDevices(appliance, String oldKey, String newKey) {
  def results = []
  if (!oldKey || !newKey || oldKey == newKey)
    return results

  getChildDevices().each { cd ->
    String dni = cd.deviceNetworkId
    String newDni = null

    if (cd.id == appliance.id) {
      newDni = newKey
    } else if (dni.startsWith(oldKey + ":")) {
      newDni = newKey + dni.substring(oldKey.length())
    } else if (!dni.startsWith(newKey + ":")) {
      log.warn("rekeyDevices: ${cd.displayName} (${dni}) does not belong to ${oldKey}; " +
        "it may be a duplicate from an older version and can be removed.")
    }

    if (newDni && newDni != dni) {
      try {
        cd.setDeviceNetworkId(newDni)
        log.info("rekeyDevices: ${cd.displayName} ${dni} -> ${newDni}")
      } catch (e) {
        log.error("rekeyDevices: could not rename ${cd.displayName} ${dni} -> ${newDni}: ${e}")
        results << "Error renaming ${cd.displayName}"
      }
    }
  }
  return results
}

/*** Event handlers ***/

/**
 * SSDP discovery responses for AlarmDecoder appliances.
 */
def ssdpHandler(evt) {
  def parsed = parseLanMessage(evt.description)
  if (!parsed?.ssdpTerm?.contains(SSDPTERM) || !parsed?.ssdpUSN)
    return

  def entry = [
    ip: parsed.networkAddress,
    port: parsed.deviceAddress,
    mac: normalizeMac(parsed.mac),
    ssdpPath: parsed.ssdpPath,
    ssdpUSN: parsed.ssdpUSN,
    ssdpTerm: parsed.ssdpTerm,
    ts: now()
  ]
  logDebug("ssdpHandler: ${entry}")

  // Expire old discoveries and add/update this one.
  def ct = now()
  def devices = getDevices().findAll { k, v ->
    v.ts && (ct - (v.ts as Long)) < DISCOVERY_EXPIRE_MS
  }
  devices[parsed.ssdpUSN.toString()] = entry
  state.devices = devices

  followAddressChange(entry)
}

/**
 * If the linked appliance shows up at a new address, update it. Its
 * network id is the MAC, so pushes keep working without a relink.
 */
private void followAddressChange(Map disc) {
  def d = getApplianceDevice()
  if (!d || !disc.mac || !disc.ip || !disc.port)
    return
  if (normalizeMac(d.getDataValue("mac")) != disc.mac)
    return

  String newUrn = "${convertHexToIP(disc.ip)}:${convertHexToInt(disc.port)}"
  String oldUrn = d.getDataValue("urn")
  if (oldUrn == newUrn)
    return

  log.info("AlarmDecoder ${disc.mac} moved from ${oldUrn} to ${newUrn}; updating link.")
  d.updateDataValue("urn", newUrn)
  if (disc.ssdpPath)
    d.updateDataValue("ssdpPath", disc.ssdpPath)
  if (disc.ssdpUSN)
    d.updateDataValue("ssdpUSN", disc.ssdpUSN)
  state.urn = newUrn
  d.initialize()
}

/**
 * Called by the appliance driver when it cannot reach the appliance.
 */
def rediscover() {
  def last = state.lastRediscover
  if (last != null && now() - (last as Long) < REDISCOVER_INTERVAL_MS)
    return
  state.lastRediscover = now()
  log.info("AlarmDecoder service: appliance unreachable, searching the network")
  discover_alarmdecoder()
}

/**
 * Hub restarted. Search for the appliance in case its address changed
 * while the hub was down. The appliance driver re-subscribes itself.
 */
def systemStartHandler(evt) {
  logTrace("systemStartHandler")
  runIn(60, "discover_alarmdecoder")
}

/**
 * Handle our child device action button events.
 */
def actionButton(id) {
  def d = getApplianceDevice()
  logDebug("actionButton: id=${id} dev=${d}")

  if (!d) {
    log.error("actionButton: Could not find the AlarmDecoder appliance device.")
    return
  }

  String action = id.toString().contains(":") ? id.toString().substring(id.toString().indexOf(':') + 1) : id.toString()

  switch (action) {
    case "disarm":
      // FIXME: Need a pin code or some way to trust the request.
      if (inputCreateDisarm)
        d.disarm()
      break
    case "exit":
      d.exit()
      break
    case "armAway":
      d.arm_away()
      break
    case "armStay":
      d.arm_stay()
      break
    case "armNight":
      d.arm_night()
      break
    case "armInstant":
      d.arm_instant()
      break
    case "chimeMode":
      d.chime()
      break
    case "alarmPanic":
      d.panic()
      break
    case "alarmAUX":
      d.aux()
      break
    case "alarmFire":
      d.fire()
      break
    case "alarmBell":
      // Turn off alarm bell indicator if pushed
      _sendEventTranslate(getChildDevice(id), "off")
      break
    default:
      if (action.startsWith("CID-") || action.startsWith("RFX-")) {
        // Clear the CID/RFX indicator when pushed.
        _sendEventTranslate(getChildDevice(id), "off")
      } else {
        log.warn("actionButton: unknown action '${action}'")
      }
  }
}

/**
 * Send value to the "<key>:<name>" button and "<key>:<name>Status" contact.
 */
private void setVirtual(String name, String value, boolean button = true, boolean status = true) {
  if (button)
    _sendEventTranslate(getChildDevice("${getDeviceKey()}:${name}"), value)
  if (status)
    _sendEventTranslate(getChildDevice("${getDeviceKey()}:${name}Status"), value)
}

def smokeSet(evt) {
  logDebug("smokeSet: ${evt.value}")
  def value = (evt.value == "detected" ? "on" : "off")
  _sendEventTranslate(getChildDevice("${getDeviceKey()}:smokeAlarm"), value)
  _sendEventTranslate(getChildDevice("${getDeviceKey()}:alarmFireStatus"), value)
}

def armAwaySet(evt) {
  logDebug("armAwaySet ${evt.value}")
  setVirtual("armAway", evt.value)
}

def armStaySet(evt) {
  logDebug("armStaySet ${evt.value}")
  setVirtual("armStay", evt.value)
}

def armNightSet(evt) {
  logDebug("armNightSet ${evt.value}")
  setVirtual("armNight", evt.value)
}

def alarmBellSet(evt) {
  logDebug("alarmBellSet ${evt.value}")
  setVirtual("alarmBell", evt.value)
}

def chimeSet(evt) {
  logDebug("chimeSet ${evt.value}")
  setVirtual("chimeMode", evt.value)
}

def exitSet(evt) {
  logDebug("exitSet ${evt.value}")
  setVirtual("exit", evt.value)
}

def perimeterOnlySet(evt) {
  logDebug("perimeterOnlySet ${evt.value}")
  setVirtual("perimeterOnly", evt.value, false, true)
}

def entryDelayOffSet(evt) {
  logDebug("entryDelayOffSet ${evt.value}")
  setVirtual("entryDelayOff", evt.value, false, true)
}

def bypassSet(evt) {
  logDebug("bypassSet ${evt.value}")
  setVirtual("bypass", evt.value, false, true)
}

def readySet(evt) {
  logDebug("readySet ${evt.value}")
  setVirtual("ready", evt.value, false, true)
}

def disarmSet(evt) {
  logDebug("disarmSet ${evt.value}")
  setVirtual("disarm", evt.value, true, false)
}

/**
 * send CID event to the correct device if one exists
 * evt.value example !LRR:001,1,CID_1406,ff
 */
def cidSet(evt) {
  logDebug("cidSet ${evt.value}")

  def parts = evt.value.split(',')
  if (parts.size() < 3) {
    log.warn("cidSet: unexpected message ${evt.value}")
    return
  }

  // 1 digit QUALIFIER 1 = Event or Open, 3 = Restore or Close
  def cidstate = (parts[2][-4..-4] == "1") ? "on" : "off"

  // 3 digit CID number
  def cidnum = parts[2][-3..-1]

  // the CID report value. Zone # or User # or ...
  def cidvalue = parts[0].split(':')[1]

  // the partition # with 0 being system
  def partition = parts[1].toInteger()

  logDebug("cidSet num:${cidnum} part: ${partition} state:${cidstate} val:${cidvalue}")

  def sent = false
  def device_name = "CID-${cidnum}-${partition}-${cidvalue}"
  getChildDevices().each {
    if (it.deviceNetworkId.contains(":CID-")) {
      // replace ? with . for a simple regex
      def match = getDeviceNamePart(it).replace("?", ".")
      if (device_name =~ /${match}/) {
        logDebug("cidSet device: ${device_name} matches ${match} sending state ${cidstate}")
        _sendEventTranslate(it, cidstate)
        sent = true
      } else {
        logDebug("cidSet device: ${device_name} no match ${match}")
      }
    }
  }

  if (!sent)
    logDebug("cidSet: no device for '${device_name}'")
}

/**
 * send RFX event to the correct device if one exists
 * evt.value example raw !RFX:123123,a0
 * eventmessage 123123:0:0:1:1:0:0
 * device <key>:RFX-123123-?-?-1-?-?-?
 */
def rfxSet(evt) {
  def parts = evt.value.split(':')
  if (parts.size() < 7) {
    log.warn("rfxSet: unexpected message ${evt.value}")
    return
  }

  def sn = parts[0]
  def bat = parts[1]
  def supv = parts[2]
  def loops = [parts[3], parts[4], parts[5], parts[6]]

  logDebug("rfxSet sn:${sn} bat:${bat} supv:${supv} loops:${loops}")

  // Zone sensors with a matching serial number.
  def d = getChildDevices().findAll {
    it.deviceNetworkId.contains(":switch") && dataValue(it, "serial") == sn
  }

  d.each {
    def zoneLoop = dataValue(it, "zoneLoop")
    def tamperLoop = dataValue(it, "tamperLoop")

    if (zoneLoop?.isInteger()) {
      def idx = zoneLoop.toInteger() - 1
      _sendEventTranslate(it, (idx in 0..3 && loops[idx] == "1") ? "on" : "off", false)
    }

    if (tamperLoop?.isInteger()) {
      def idx = tamperLoop.toInteger() - 1
      it.sendEvent(name: "tamper", value: (idx in 0..3 && loops[idx] == "1") ? "detected" : "clear")
    }

    it.sendEvent(name: "low_battery", value: (bat == "1").toString())

    if (supv == "1")
      it.sendEvent(name: "last_checkin", value: now())
  }

  // RFX- button devices matched by mask.
  def device_name = "RFX-${sn}-${bat}-${supv}-${loops.join('-')}"
  getChildDevices().each {
    if (it.deviceNetworkId.contains(":RFX-")) {
      def sp = getDeviceNamePart(it).split("-")
      if (sp.size() < 8)
        return

      def match = sp[0] + "-" + sp[1] + "-*"
      if (device_name =~ /${match}/) {
        def tot = 0
        if (sp[2] == "1" && bat == "1") tot++
        if (sp[3] == "1" && supv == "1") tot++
        for (int i = 0; i < 4; i++) {
          if (sp[4 + i] == "1" && loops[i] == "1") tot++
        }

        _sendEventTranslate(it, (tot > 0 ? "on" : "off"))
        logDebug("rfxSet device: ${device_name} matches ${match} sending state ${tot}")
      }
    }
  }
}

/**
 * Zone fault event from the appliance; open matching zone sensors.
 */
def zoneOn(evt) {
  logDebug("zoneOn: ${evt.value}")
  setZoneSensors(evt.value, "on")
}

/**
 * Zone restore event from the appliance; close matching zone sensors.
 */
def zoneOff(evt) {
  logDebug("zoneOff: ${evt.value}")
  setZoneSensors(evt.value, "off")
}

private void setZoneSensors(zone, String value) {
  if (!zone?.toString()?.isInteger())
    return
  int zoneNum = zone.toString().toInteger()

  // All zone sensors with a matching zone number that are not RF devices.
  def d = getChildDevices().findAll {
    if (!it.deviceNetworkId.contains(":switch") || dataValue(it, "serial"))
      return false
    def z = dataValue(it, "zone")
    return z?.isInteger() && z.toInteger() == zoneNum
  }

  if (d) {
    d.each { _sendEventTranslate(it, value) }
  } else {
    logDebug("zone ${zoneNum}: no virtual device has this zone number.")
  }
}

/**
 * Handle Hubitat Safety Monitor (HSM) events and arm/disarm the panel.
 */
def monitorAlarmHandler(evt) {
  if (settings.monIntegration == false)
    return

  logDebug("monitorAlarmHandler: ${evt.value} (last ${state.lastMONStatus})")
  state.lastMONStatus = evt.value

  def device = getApplianceDevice()
  if (!device)
    return

  def armed = device.getStateValue("panel_armed") ||
    device.getStateValue("panel_armed_stay") ||
    device.getStateValue("panel_armed_night")

  switch (evt.value) {
    case "armingAway":
    case "armedAway":
      if (!armed) sendMonitorCommand(device, "arm_away")
      else logTrace("monitorAlarmHandler: panel already armed, not sending arm_away")
      break
    case "armingHome":
    case "armedHome":
      if (!armed) sendMonitorCommand(device, "arm_stay")
      else logTrace("monitorAlarmHandler: panel already armed, not sending arm_stay")
      break
    case "armingNight":
    case "armedNight":
      if (!armed) sendMonitorCommand(device, "arm_night")
      else logTrace("monitorAlarmHandler: panel already armed, not sending arm_night")
      break
    case "disarmed":
    case "allDisarmed":
      if (armed) sendMonitorCommand(device, "disarm")
      else logTrace("monitorAlarmHandler: panel already disarmed, not sending disarm")
      break
    default:
      logDebug("Unknown HSM alarm value: ${evt.value}")
  }
}

/**
 * Send a command once. HSM reports "arming" then "armed" for the same
 * request; do not send the same command twice within a minute.
 */
private void sendMonitorCommand(device, String command) {
  def last = state.lastMonitorCommand
  if (last?.command == command && last?.time && now() - (last.time as Long) < 60000) {
    logTrace("monitorAlarmHandler: ${command} already sent")
    return
  }
  state.lastMonitorCommand = [command: command, time: now()]
  switch (command) {
    case "arm_away": device.arm_away(); break
    case "arm_stay": device.arm_stay(); break
    case "arm_night": device.arm_night(); break
    case "disarm": device.disarm(); break
  }
}

/**
 * Handle alarm status events from the AlarmDecoder and update HSM.
 */
def alarmdecoderAlarmHandler(evt) {
  if (settings.monIntegration == false || settings.monChangeStatus == false)
    return

  logDebug("alarmdecoderAlarmHandler: ${evt.value} (last ${state.lastAlarmDecoderStatus})")
  state.lastAlarmDecoderStatus = evt.value

  def msg = null
  def nstate = null
  switch (evt.value) {
    case "stay":
      msg = "armHome"
      nstate = "armedHome"
      break
    case "away":
      msg = "armAway"
      nstate = "armedAway"
      break
    case "night":
      msg = "armNight"
      nstate = "armedNight"
      break
    case "off":
      msg = "disarm"
      nstate = "disarmed"
      break
  }

  if (!msg) {
    log.warn("alarmdecoderAlarmHandler: unknown value ${evt.value}")
    return
  }

  logDebug("alarmdecoderAlarmHandler: hsmSetArm ${msg} last ${state.lastMONStatus} new ${nstate}")

  // Update last known MON state to prevent a loop.
  state.lastMONStatus = nstate

  sendLocationEvent(name: "hsmSetArm", value: msg)
}

/*** Utility/Misc ***/

/**
 * Enable primary network and system subscriptions
 */
def initSubscriptions() {
  logDebug("initSubscriptions: Subscribe to handlers")
  subscribe(location, "hsmStatus", monitorAlarmHandler)
  subscribe(location, "ssdpTerm.${SSDPTERM}", ssdpHandler)
  subscribe(location, "systemStart", systemStartHandler)
}

/**
 * Send a UPNP discovery message from the hub to the local network.
 */
def discover_alarmdecoder() {
  logDebug("discover_alarmdecoder")
  sendHubCommand(new hubitat.device.HubAction("lan discovery ${SSDPTERM}", hubitat.device.Protocol.LAN))
}

/**
 * Call refresh() on the AlarmDecoder appliance device.
 */
def refresh_alarmdecoders() {
  logDebug("refresh_alarmdecoders")
  def device = getApplianceDevice()
  if (!device)
    return
  if (device._get_api_key()) {
    device.refresh()
  } else {
    log.error("refresh_alarmdecoders no API KEY for: ${device} @ ${device.getDataValue('urn')}")
  }
}

/**
 * return the map of discovered AlarmDecoders keyed by ssdpUSN
 *  [ "uuid:...": [ip: "C0A8016F", port: "1388", mac: "...", ssdpPath: "...",
 *                 ssdpUSN: "uuid:...", ssdpTerm: "...", ts: 1234 ] ]
 */
def getDevices() {
  if (!state.devices) {
    state.devices = [:]
  }
  return state.devices
}

/**
 * Return the AlarmDecoder appliance child device, or null.
 */
def getApplianceDevice() {
  def all = getChildDevices().findAll { it.typeName == APPLIANCE_TYPE }
  if (!all)
    return null
  if (all.size() == 1)
    return all[0]

  // Duplicates from an older version. Prefer the active one.
  def key = state.deviceKey ?: state.ip
  return all.find { it.deviceNetworkId == key } ?: all[0]
}

/**
 * First install: create the appliance from the selected discovery and all
 * the virtual devices.
 */
def addExistingDevices() {
  logDebug("addExistingDevices: ${input_selected_devices}")

  if (getApplianceDevice()) {
    log.warn("addExistingDevices: appliance already installed, not creating another one.")
    return
  }

  def disc = getSelectedDiscovery()
  if (!disc) {
    log.error("addExistingDevices: selected device ${input_selected_devices} was not found " +
      "in the discovery list. Open the app and select it again.")
    return
  }

  if (createApplianceDevice(disc)) {
    createVirtualDevices()
  }
}

/**
 * Create the appliance device. Its network id is the MAC address.
 */
private createApplianceDevice(Map disc) {
  String mac = normalizeMac(disc.mac)
  if (!mac) {
    log.error("createApplianceDevice: discovered device has no MAC address: ${disc}")
    return null
  }

  String urn = "${convertHexToIP(disc.ip)}:${convertHexToInt(disc.port)}"
  state.deviceKey = mac
  state.urn = urn
  state.schemaVersion = SCHEMA_VERSION

  logDebug("createApplianceDevice: urn ${urn} mac ${mac}")

  def d = getChildDevice(mac)
  if (d)
    return d

  try {
    d = addChildDevice(APPNAMESPACE, APPLIANCE_TYPE, mac,
      [
        name: APPLIANCE_TYPE,
        label: guiname,
        isComponent: false,
        data: [
          mac: mac,
          ssdpUSN: disc.ssdpUSN,
          urn: urn,
          ssdpPath: disc.ssdpPath
        ]
      ]
    )
    d.sendEvent(name: "panel_state", value: "notready", isStateChange: true)
  } catch (e) {
    log.error("Error creating appliance device ${guiname}: ${e}")
    return null
  }
  return d
}

/**
 * Create every virtual device that does not exist yet. Existing devices
 * are left alone. Returns a list of result messages.
 */
def createVirtualDevices() {
  def results = []
  if (!getDeviceKey()) {
    log.error("createVirtualDevices: no appliance linked")
    return results
  }

  // Zone sensors: create the missing ones of each type at free indexes.
  def zoneDevices = getChildDevices().findAll { it.deviceNetworkId ==~ /.*:switch\d+$/ }
  def used = zoneDevices.collect {
    (it.deviceNetworkId =~ /:switch(\d+)$/)[0][1].toInteger()
  } as Set
  int nextIdx = 1

  ZONE_TYPES.each { type, cfg ->
    int wanted = safeInt(settings[cfg.setting])
    int have = zoneDevices.count { it.typeName == cfg.driver }
    for (int n = have; n < wanted; n++) {
      while (used.contains(nextIdx))
        nextIdx++
      if (addZone(nextIdx, cfg.driver))
        results << "Created ${type} zone sensor #${nextIdx}"
      used << nextIdx
    }
  }

  // Smoke alarm
  if (createChild("AlarmDecoder virtual smoke alarm", "smokeAlarm", "${sname} Smoke Alarm",
      [name: "smoke", value: "clear"]))
    results << "Created Smoke Alarm"

  // [suffix, label, button, contact]
  def virtualDevices = [
    ["armStay", "Stay", true, true],
    ["armAway", "Away", true, true],
    ["armNight", "Night", true, true],
    ["exit", "Exit", true, true],
    ["chimeMode", "Chime", true, true],
    ["bypass", "Bypass", false, true],
    ["ready", "Ready", false, true],
    ["perimeterOnly", "Perimeter Only", false, true],
    ["entryDelayOff", "Entry Delay Off", false, true],
    ["alarmBell", "Alarm Bell", true, true],
    ["alarmFire", "Fire Alarm", true, true],
    ["alarmPanic", "Panic Alarm", true, false],
    ["alarmAUX", "AUX Alarm", true, false],
    ["armInstant", "Instant Arm", true, false]
  ]
  if (inputCreateDisarm)
    virtualDevices << ["disarm", "Disarm", true, false]

  virtualDevices.each { v ->
    results.addAll(addAD2VirtualDevices(v[0], v[1], false, v[2], v[3]))
  }

  log.info("createVirtualDevices: ${results ?: 'nothing to create'}")
  return results
}

/**
 * Create one zone sensor <key>:switch<idx>.
 */
private boolean addZone(int idx, String driver) {
  log.info("addZone: creating zone sensor #${idx} (${driver})")
  def d = createChild(driver, "switch${idx}", "${sname} Zone Sensor #${idx}", null)
  if (!d)
    return false

  // Set default sensor state.
  _sendEventTranslate(d, (settings.defaultSensorToClosed == true) ? "off" : "on")
  return true
}

/**
 * Add Virtual button and contact. Each is created only if missing.
 */
private List addAD2VirtualDevices(name, label, initstate, createButton, createContact) {
  def results = []

  if (createButton) {
    if (createChild("AlarmDecoder action button indicator", name, "${sname} ${label}",
        [name: "switch", value: (initstate ? "on" : "off")]))
      results << "Created ${label}"
  }

  if (createContact) {
    if (createChild("AlarmDecoder status indicator", "${name}Status", "${sname} ${label} Status",
        [name: "contact", value: (initstate ? "closed" : "open")]))
      results << "Created ${label} Status"
  }

  return results
}

/**
 * Create child "<key>:<suffix>" if it does not exist. Returns the new
 * device, or null if it exists or could not be created.
 */
private createChild(String driver, String suffix, String label, Map initEvent, String name = null) {
  String dni = "${getDeviceKey()}:${suffix}"
  if (getChildDevice(dni)) {
    logDebug("createChild: ${dni} already exists")
    return null
  }

  try {
    def d = addChildDevice(APPNAMESPACE, driver, dni,
      [name: name ?: dni, label: label, isComponent: false])
    if (initEvent)
      d.sendEvent(initEvent + [isStateChange: true])
    return d
  } catch (e) {
    log.error("Error creating device ${dni} (${driver}): ${e}")
    return null
  }
}

/**
 * Configure subscriptions to the events the appliance device sends.
 */
private def configureDeviceSubscriptions() {
  logDebug("configureDeviceSubscriptions")
  def device = getApplianceDevice()
  if (!device) {
    log.warn("configureDeviceSubscriptions: no appliance device found.")
    return
  }

  // Zone changes
  subscribe(device, "zone-on", zoneOn, [filterEvents: false])
  subscribe(device, "zone-off", zoneOff, [filterEvents: false])

  // Panel alarm status for HSM
  subscribe(device, "alarmStatus", alarmdecoderAlarmHandler, [filterEvents: false])

  // Virtual device updates
  subscribe(device, "smoke-set", smokeSet, [filterEvents: false])
  subscribe(device, "arm-away-set", armAwaySet, [filterEvents: false])
  subscribe(device, "arm-stay-set", armStaySet, [filterEvents: false])
  subscribe(device, "arm-night-set", armNightSet, [filterEvents: false])
  subscribe(device, "chime-set", chimeSet, [filterEvents: false])
  subscribe(device, "exit-set", exitSet, [filterEvents: false])
  subscribe(device, "perimeter-only-set", perimeterOnlySet, [filterEvents: false])
  subscribe(device, "entry-delay-off-set", entryDelayOffSet, [filterEvents: false])
  subscribe(device, "bypass-set", bypassSet, [filterEvents: false])
  subscribe(device, "alarmbell-set", alarmBellSet, [filterEvents: false])
  subscribe(device, "ready-set", readySet, [filterEvents: false])
  subscribe(device, "disarm-set", disarmSet, [filterEvents: false])
  subscribe(device, "cid-set", cidSet, [filterEvents: false])
  subscribe(device, "rfx-set", rfxSet, [filterEvents: false])
}

/**
 * Re-link the appliance to the selected discovered device. Only the
 * appliance's address data changes; devices are re-keyed only if the MAC
 * changed (replaced hardware).
 */
private List relinkAppliance(selected) {
  def results = []
  def d = getApplianceDevice()
  if (!d)
    return ["Error: no ${lname} appliance device found"]

  def disc = getSelectedDiscovery(selected)
  if (!disc)
    return ["Error: the selected device is no longer in the discovery list. Select it again."]

  String urn = "${convertHexToIP(disc.ip)}:${convertHexToInt(disc.port)}"
  String newMac = normalizeMac(disc.mac)
  String oldKey = d.deviceNetworkId

  try {
    if (newMac && newMac != oldKey) {
      results.addAll(rekeyDevices(d, oldKey, newMac))
      state.deviceKey = newMac
      d.updateDataValue("mac", newMac)
      results << "Moved device ids from ${oldKey} to ${newMac}"
    }

    d.updateDataValue("urn", urn)
    if (disc.ssdpPath)
      d.updateDataValue("ssdpPath", disc.ssdpPath)
    if (disc.ssdpUSN)
      d.updateDataValue("ssdpUSN", disc.ssdpUSN)
    state.urn = urn

    app.removeSetting("input_selected_devices")

    unsubscribe()
    initSubscriptions()
    configureDeviceSubscriptions()
    d.initialize()

    results << "Success linking ${d.displayName} to ${urn}"
    log.info("relinkAppliance: linked ${d.displayName} to ${urn} (${newMac ?: oldKey})")
  } catch (e) {
    log.error("relinkAppliance: error linking to ${urn}: ${e}")
    results << "Error linking to ${urn}: ${e}"
  }
  return results
}

/**
 * Convert from internal format networkAddress:C0A8016F to a real IP address
 * string ex. 192.168.1.111
 */
private String convertHexToIP(hex) {
  [convertHexToInt(hex[0..1]),
    convertHexToInt(hex[2..3]),
    convertHexToInt(hex[4..5]),
    convertHexToInt(hex[6..7])
  ].join(".")
}

/**
 * convert hex encoded string to integer
 */
private Integer convertHexToInt(hex) {
  Integer.parseInt(hex.toString(), 16)
}

/**
 * Uppercase MAC address without separators, as Hubitat uses for DNIs.
 */
private String normalizeMac(mac) {
  if (!mac)
    return null
  String m = mac.toString().replaceAll(/[^0-9A-Fa-f]/, "").toUpperCase()
  return m.length() == 12 ? m : null
}

/**
 * The key all device network ids start with: the appliance MAC address.
 * Older installs used the hex IP address (state.ip) until migrated.
 */
private String getDeviceKey() {
  return state.deviceKey ?: (state.ip ?: "")
}

/**
 * Build a host address for web requests.
 *  ex. AABBCCDD:XXXX -> 192.168.1.1:5000
 */
private getHostAddressFromDNI(d) {
  def ip = ""
  def port = ""
  if (d) {
    def parts = d.toString().split(":")
    if (parts.size() == 2) {
      ip = convertHexToIP(parts[0])
      port = convertHexToInt(parts[1])
    }
  }
  return ip + ":" + port
}

/**
 * Return the part of a device network id after the key.
 *   AABBCCDDEEFF:ZZZZZZZZZ -> ZZZZZZZZZ
 */
private getDeviceNamePart(d) {
  String dni = d.deviceNetworkId
  int i = dni.indexOf(':')
  return (i >= 0) ? dni.substring(i + 1).trim() : ""
}

/**
 * Device data value with "null"/"" treated as missing.
 */
private String dataValue(d, String name) {
  def v = d.getDataValue(name)
  return (v == null || v == "null" || v.toString().trim() == "") ? null : v.toString().trim()
}

private int safeInt(value) {
  if (value == null)
    return 0
  try {
    return value.toString().toBigDecimal().intValue()
  } catch (e) {
    return 0
  }
}

/**
 * Send a state change to an AD2 virtual device adjusting it to
 * the devices actual capabilities and inverting if preferred.
 *
 * ad2d: The device handle.
 * state: [on, off] where "on" is the alerting state
 *   Switch: on/off, Contact Sensor: open/closed,
 *   Motion: active/inactive, Smoke/CO/Shock: detected/clear
 */
def _sendEventTranslate(ad2d, state, stateChange = true) {
  if (!ad2d)
    return

  boolean on = (state == "on")
  if (ad2d.getDataValue("invert") == "true")
    on = !on

  CAPABILITY_EVENTS.each { cap, ev ->
    if (ad2d.hasCapability(cap)) {
      ad2d.sendEvent(
        name: ev[0],
        value: (on ? ev[1] : ev[2]),
        isStateChange: stateChange
      )
    }
  }
}

def logDebug(msg) {
  if (settings?.debugOutput) {
    log.debug msg
  }
}

def logTrace(msg) {
  if (settings?.traceOutput) {
    log.trace msg
  }
}
