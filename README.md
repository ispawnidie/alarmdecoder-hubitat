# TOC

-   [Introduction](#introduction)
-   [Requirements](#requirements)
-   [Features](#features)
-   [Setup](#setup)  
    -- [Install Package](#install-package)  
    -- [Upgrading from 3.x](#upgrading-from-3x)  
    -- [Obtain an API key from the AlarmDecoder webapp](#obtain-an-api-key-from-the-alarmdecoder-webapp)  
    -- [Enabling Hubitat UPNP/SSDP Integration in the Webapp](#enabling-hubitat-upnpssdp-integration-in-the-webapp)  
    -- [Install the service](#install-the-service)  
    -- [Configure Contact ID switches](#configure-contact-id-switches)  
    -- [Configure RFX switches for Ademco 58XX sensors](#configure-rfx-switches-for-ademco-58xx-sensors)  
-   [Additional Info](#additional-info)  
    -- [Provided virtual devices](#provided-virtual-devices)  
    -- [Alarm Decoder Device Drivers](#alarm-decoder-device-drivers)  
    -- [Connection handling and IP address changes](#connection-handling-and-ip-address-changes)  
    -- [Rebuilding virtual devices](#rebuilding-virtual-devices)  
    -- [HSM integration and SecurityKeypad](#hsm-integration-and-securitykeypad)  
    -- [Known Issues](#known-issues)  

# Introduction

This project provides support for the AlarmDecoder webapp UPNP/SSDP integration with Hubitat home automation platforms.

# Requirements

The AlarmDecoder webapp and hub use UDP broadcast packets for discovery. The hub and webapp must be located on the same layer 2 network.

-   AlarmDecoder webapp 0.8.3+   
-   Hubitat Elevation platform version 2.2.0 or newer

# Features

-   Arm, disarm, toggle chime, or panic your alarm system from within Hubitat.
-   Provides virtual sensors that can be tied to zones on your panel to allow automation based on zones faulting and restoring.
-   Provides virtual momentary switches with indicators for arming away, stay and toggling chime mode with each switch indicating the current state. Alexa<sup>[#warn](#warn)</sup> and other systems are able to activate these switches to allow a wide array of alarm panel control possibilities.
-   Provide virtual contact sensors for "Ready" and "Alarm Bell" indications on the alarm panel.
-   Provides a virtual "Smoke Alarm" that can be integrated with HSM or other systems to control state during a fire event such as turning all lights on.
-   Provides the ability to create virtual switches that can be tied to specific Contact ID report codes from the alarm panel. As an example a zone setup as a Carbon Monoxide alarm can be directly tied to a virtual switch that will OPEN in the event it triggers using Contact ID 162.
-   Provides the ability to create virtual switches that can be tied to specific 5800 RF devices. Typical use case is using a 5800micra sensor to monitor open/close of secure areas but not require panel zone programming to use. Just record its serial number and tie it to a virtual device using the serial number only.
-   Hubitat Safety Monitor (HSM) integration.  
    One-way - Arm or disarm your panel when the HSM status is changed.  
    Two-way - Change HSM's status when your panel is armed or disarmed.  
-   The **AlarmDecoder UI** device implements the standard Hubitat **SecurityKeypad** capability (armAway, armHome, armNight, disarm) so it works with dashboards and Rule Machine.
-   Follows the AlarmDecoder to a new IP address automatically and reports a `networkStatus` of online/offline.
-   Change virtual device drivers in the device page to change device capabilities and the system will adjust event types to match the device. Change a Zone Sensor to a Virtual Smoke Alarm and it will report 'clear' or 'detected'. This allows changing of device types to match what is needed for the task.  

<a name="warn">#warn</a>: Alexa and other systems may be confused by the device name to send ON or OFF action too. This can be done using fuzzy AI logic than can cause the system to send the event to a Panic or Alarm action button triggering an alarm. Thoughtful naming of devices as well as restricting access is advised before allowing these external systems to access the virtual buttons.</sup>  

# Setup

## Install Package

The easiest way to install AlarmDecoder for Hubitat is to use [Hubitat Package Manager](https://community.hubitat.com/t/beta-hubitat-package-manager/38016). Use **Install** > **From a URL** with:

    https://raw.githubusercontent.com/ispawnidie/alarmdecoder-hubitat/master/packageManifest.json

To install manually, add the app from `apps/alarmdecoder-service.groovy` in **Apps Code** and every driver in `drivers/` in **Drivers Code** (use the **Import** button with the raw GitHub URL of each file).

## Upgrading from 3.x

Version 4.0.0 changes the device network ids (DNI) of all AlarmDecoder devices from the appliance's hex IP address to its MAC address. This lets Hubitat keep delivering push notifications after the appliance gets a new IP address.

1.  Update the app and all drivers (Hubitat Package Manager **Update** does this).
2.  Open **Apps** > **AlarmDecoder service** and press **Done**.
3.  The logs show one `rekeyDevices` line per device. The device ids do not change, so rules, dashboards and HSM settings keep working.

If the logs say a device "does not belong" to the appliance it is a duplicate left over from an earlier relink or rebuild and can be deleted.

## Obtain an API key from the AlarmDecoder webapp

1.  Navigate to the API section of webapp on your local network: (<https://alarmdecoder.local/api/>)
2.  Click **Manage API keys**
3.  Click **Generate** for the desired webapp user (eg. `admin`)

## Enabling Hubitat UPNP/SSDP Integration in the Webapp

1.  Log into your AlarmDecoder webapp.
2.  Click Settings
3.  Click Notifications
4.  Click the New Notification button
5.  Set the Notification Type to 'UPNP Push'
6.  Enter a description ex 'UPNP PUSH'
7.  Press Next
8.  Press Save

-   notes  
      a.  If the AlarmDecoder Web App restarts it will lose subscriptions. It may take 5 minutes to restore PUSH notification.  
      b.  Pressing **Save Preferences** or **Initialize** on the **AlarmDecoder UI** device forces a new subscription.  

## Install the service

1.  Go to **Apps** > **Add User App** and select **AlarmDecoder service**.
2.  Tap **Install Service**, then **[ * TAP HERE * ]** to search for the appliance.
3.  Select the AlarmDecoder from the list and press **Next**.
4.  Enter how many virtual sensors of each type to create and the HSM options, then press **Done**.
5.  Open the **AlarmDecoder UI** device, enter the **API Key**, **Alarm Code** and **Panel Type**, then press **Save Preferences**.
6.  Open each **Security Zone Sensor #N** device and set its **Zone Number** to the panel zone it should follow.

## Configure Contact ID switches

**CID** or **Contact ID** is a prevalent and respected format for communications between alarms and the systems at alarm monitoring agencies that they report to.

If the Ademco panel has an existing Internet or cellular communicator then the AD2 is able to capture these messages and PUSH them to the home automation system as virtual switches. For DSC Power Series panels this feature is a Zero configuration feature(it just works). With Ademco panels a communicator is required or you can enable LRR messaging in your panel programming and enable LRR Emulation on the AlarmDecoder. In both panels it may be necessary to Enable / Disable reporting for some types of events.

1.  Open **Apps** > **AlarmDecoder service**
2.  Select **Contact ID Device Management**
3.  Select **Add New Contact ID Virtual Switch**
4.  Select the CID number pattern  
    -- 000 - Other / Custom  
    -- 100-102 - ALL Medical alarms  
    -- 110-118 - ALL Fire alarms  
    -- 120-126 - ALL Panic alarms  
    -- 130-139 - ALL Burglar alarms  
    -- 140-149 - ALL General alarms  
    -- 150-169 - ALL 24 HOUR AUX alarms  
    -- 154 - Water Leakage  
    -- 158 - High Temp  
    -- 162 - Carbon Monoxide Detected  
    -- 301 - AC Loss  
    -- 3?? - All System Troubles  
    -- 401 - Arm AWAY OPEN/CLOSE  
    -- 441 - Arm STAY OPEN/CLOSE  
    -- 4[0,4]1 - Arm Stay or Away OPEN/CLOSE  
5.  Enter the User or ZONE to match or ??? to match all.
6.  Enter the partition to match 0(system), 1, 2 or ? to match all.
7.  Select **Add new CID switch** to confirm.
8.  The switch will be created and you can see it under **Devices**.
9.  Change the **Type** of the new **CID virtual switch** as desired by editing the device. If the application needs the device to be a Smoke Alarm then change its device type to **AlarmDecoder virtual smoke alarm** in the device editor.

## Configure RFX switches for Ademco 58XX sensors

All 5800 sensors within range of the 5800 receiver are able to be monitored for events.  All that is needed is the serial number. It is not necessary to program the 5800 device into the panel for this to work.

1.  Open **Apps** > **AlarmDecoder service**
2.  Select **RFX Device Management**
3.  Select **[ * TAP HERE * ]** to add a new RFX virtual device
4.  Enter the 7 digit serial number including leading 0's ex 0123020
5.  Enter **?**, **0** or **1** for each loop or attribute to watch for events. ? will ignore the attribute and a value will match for it.
6.  Select **[ * TAP HERE * ]** to confirm.
7.  The switch will be created and you can see it under **Devices**.
8.  Change the **Type** of the new **RFX virtual switch** as desired by editing the device. If the application needs the device to be a Smoke Alarm then change its device type to **AlarmDecoder virtual smoke alarm** in the device editor.

# Additional Info

## Provided virtual devices

  -   AlarmDecoder UI  
     Network Mask: AlarmDecoder MAC address  
     Description: Main service device provides a simple user interface to manage the alarm.  
     -- capabilities [Actuator, Initialize, Refresh, SecurityKeypad]  
     -- attributes [panel_state, alarmStatus, securityKeypad, networkStatus, lastContact, faultedZones, zoneStatus1-12]  
  -   Security<sup>[#1](#vdevicenames)</sup> Alarm Bell  
     Network Mask: \*:alarmBell  
     Description: An indicator to show the panel bell state and button to clear.  
     Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: manually set state to off and clear]  
     -- states [on(Alarming), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Alarm Bell Status  
     Network Mask: \*:alarmBellStatus  
     Description: An indicator to show the panel bell state.  
     Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Alarming), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Chime  
     Network Mask: \*:chimeMode  
     Description: indicator to show the Chime state and button to toggle.  
     Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
      -- capabilities [Momentary, Switch]  
      -- actions [push: toggle chime mode]  
      -- states [on(Enabled), off]  
      -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Chime Status  
    Network Mask: \*:chimeModeStatus  
    Description: An indicator to show the chime state.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Enabled), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Ready Status  
    Network Mask: \*:readyStatus  
    Description: An indicator to show the panel ready to arm state.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(READY), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Bypass Status  
    Network Mask: \*:bypassStatus  
    Description: An indicator to show if the panel has a bypassed zone.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Zone(s) bypassed), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Entry Delay Off state  
    Network Mask: \*:entryDelayOffStatus  
    Description: An indicator to show if the panel has exit delay off set.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Exit Delay OFF), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Perimeter Only state  
    Network Mask: \*:perimeterOnlyStatus  
    Description: An indicator to show if the panel has perimeter only set.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Perimeter Only ON), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Smoke Alarm  
    Network Mask: \*:smokeAlarm  
    Description: An indicator to show the panel fire state.  
    Default Handler: **AlarmDecoder virtual smoke alarm**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Smoke Detector]  
     -- states [clear, detected]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Disarm  
    Network Mask: \*:disarm  
    Description: indicator to show the arm state and button to disarm.  
    Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: disarm panel]  
     -- states [on(Armed), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Stay  
    Network Mask: \*:armStay  
    Description: indicator to show the arm stay state and button to arm stay.  
    Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: arm stay]  
     -- states [on(Armed Stay), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Stay Status  
    Network Mask: \*:armStayStatus  
    Description: An indicator to show if the panel is armed in stay mode.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Armed Stay), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Away  
    Network Mask: \*:armAway  
    Description: indicator to show the arm away state and button to arm away.  
    Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: arm away]  
     -- states [on(Armed Away), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Away Status  
    Network Mask: \*:armAwayStatus  
    Description: An indicator to show if the panel is armed in stay mode.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Armed Away), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Exit  
    Network Mask: \*:exit  
    Description: indicator to show the exit state.  
    Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: request exit]  
     -- states [on(Exit now active), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Exit Status  
    Network Mask: \*:exitStatus  
    Description: An indicator to show if the panel exit mode is active.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Exit now active), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Panic Alarm  
    Network Mask: \*:alarmPanic  
    Description: Button to send Panic Alarm signal to the panel.  
    Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: request panic]  
     -- states N/A<sup>[#1](#notsupported)</sup>
     \-- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> AUX(Medical) Alarm  
    Network Mask: \*:alarmAUX  
    Description: Button to send AUX(Medical) Alarm signal to the panel.  
    Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: request aux alarm]  
     -- states N/A<sup>[#1](#notsupported)</sup>
     \-- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Fire Alarm  
    Network Mask: \*:alarmFire  
    Description: An indicator to show the panel fire state and button to clear.  
    Default Handler:  **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities [Momentary, Switch]  
     -- actions [push: manually set state to off and clear]  
     -- states [on(Alarming), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Fire Alarm Status  
    Network Mask: \*:alarmFireStatus  
    Description: An indicator to show the panel fire alarm state.  
    Default Handler: **AlarmDecoder status indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Alarming), close]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   Security<sup>[#1](#vdevicenames)</sup> Zone Sensor #N<sup>[#4](#zonenumbers)</sup>  
    Network Mask: \*:switch[#N]  
    Description: An indicator to show the zone state.  
    Default Handler: **AlarmDecoder virtual contact sensor**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- states [open(Alarming), close]  
     -- preferences \[ invert:[true, false], zone: Numeric zone associated with this device.]  
  -   CID-**_AAA_**-**_B_**-**_CCC_**<sup>[#2](#cidmask)</sup>  
    Network Mask: \*:CID-AAAA-B-CCC  
    Description: Indicates the state of the given Contact ID report state.  
    Default Handler: **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- actions [push: manually set state to off and clear]  
     -- states [on(Active), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  
  -   RFX-AAAAAA-B-C-D-E-F-G<sup>[#3](#rfxmask)</sup>
    Network Mask: \*:RFX-AAAAAAA-B-C-D-E-F-G  
    Description: Indicates the state of the given RFX sensor.  
    Default Handler: **AlarmDecoder action button indicator**<sup>[#5](#flexible_handlers)</sup>  
     -- capabilities  [Contact sensor]  
     -- actions [push: manually set state to off and clear]  
     -- states [on(Active), off]  
     -- preferences \[ invert:[true, false], zone: N/A]  

<a name="vdevicenames">#1</a>: The device label prefix is configurable before install by changing `@Field sname` at the top of the **_AlarmDecoder Service_** code. Labels can be changed after installing but the device network id must not be modified.  

All device network ids start with the AlarmDecoder MAC address, ex. `B827EB123456:armAway`.  

<a name="cidmask">#2</a>: **_AAA_** is the Contact ID number **_B_** is the partition and **_CCC_** is the zone or user code to match with '???' matching all. Ex. CID-401-012 will monitor user 012 arming/disarming. Supports regex in the deviceNetworkId to allow to create devices that can trigger on multiple CID messages such as **_"CID-4[0,4]]1-1-???"_** will monitor all users for arming/disarming away or stay on partition 1.  

<a name="rfxmask">#3</a>:AAAAAA is the RF Serial Number B is battery status, C is supervisor event(ignore with ?), D is loop0(ignore with ?), E is loop1(ignore with ?), F is loop2(ignore with ?) and E is loop3(ignore with ?). Ex. RFX-123456-?-?-1-?-?-? will monitor 5800 RF sensor with serial number 123456 and loop1 for changes.  

<a name="zonenumbers">#4</a>: The number assigned initially to each device zone name is sequential and arbitrary. The actual zone tracked for each device is configured with the **Zone Number** preference on each zone sensor device. There is no limit on the number of zones.  So 'Security Zone Sensor #1' could actually be zone 20.  Rename these as needed.

<a name="flexible_handlers">#5</a>: Each AlarmDecoder Virtual device receives a default type when it is created. This Device Type or Device Handler can be changed using the device editor. [Several example device types](#devicetypes) are provided and more can be created using the examples as reference.  

<a name="devicetypes"></a>

## Alarm Decoder Device Drivers

The Device Type or Device Handler for a given virtual device can be changed at any time. If the application requires a Smoke Detector and does not support Contact Sensors simply change the Type in the device editor. AlarmDecoder virtual devices will receive a translated message from on/off to a message appropriate to the devices type and capabilities.  

-   AlarmDecoder virtual contact sensor  
    capabilities [Contact Sensor]  
-   AlarmDecoder action button indicator  
    capabilities [Momentary, Switch]  
-   AlarmDecoder status indicator  
    capabilities [Contact Sensor]  
-   AlarmDecoder virtual carbon monoxide detector  
    capabilities [Carbon Monoxide Detector]  
-   AlarmDecoder virtual shock sensor  
    capabilities [Shock Sensor]  
-   AlarmDecoder virtual motion detector  
    capabilities [Motion Sensor]  
-   AlarmDecoder virtual smoke alarm  
    capabilities [Smoke Detector]

## Connection handling and IP address changes

-   The **AlarmDecoder UI** device renews its UPnP push subscription every 5 minutes and after a hub restart.
-   Its `networkStatus` attribute is `online` while the appliance answers and `offline` after a failed request or 16 minutes without contact. `lastContact` shows the last time it answered.
-   The service searches the network every 30 minutes, after a hub restart, and whenever the appliance stops answering. If the AlarmDecoder (matched by MAC address) is found at a new IP address or port, the service updates the link and re-subscribes. No relink or rebuild is needed.
-   **Update Service Settings** in the app can still be used to link to a different appliance by hand. If the new appliance has a different MAC address (replaced hardware) all devices are renamed to the new MAC; they are never re-created.
-   A DHCP reservation for the AlarmDecoder is still recommended.

## Rebuilding virtual devices

**Rebuild Virtual Devices** only creates devices that are missing. Existing devices, their settings and their zone numbers are kept. To add zone sensors, raise the count for that sensor type and confirm; new sensors get the next free `switchN` number. Rebuild never creates a second **AlarmDecoder UI** device.

## HSM integration and SecurityKeypad

-   **Integrate with Hubitat Safety Monitor** arms or disarms the panel when HSM changes (both "arming" and "armed" states are handled and the command is only sent once).
-   **Automatically change Hubitat Safety Monitor status** updates HSM when the panel is armed or disarmed at a keypad.
-   The **AlarmDecoder UI** device also supports the SecurityKeypad capability. Do not add it as a keypad in HSM *and* enable the app's HSM integration at the same time, or both will send the same arm/disarm commands.
-   User codes, entry and exit delays are managed at the panel; the SecurityKeypad code commands only log a message.

## Known Issues

-   DSC:  
    a. Extra zones will show up in the zone list.  
    b. Arming STAY shows AWAY until after EXIT state.  
-   ADEMCO:  
    a. As with a regular keypad it is necessary to disarm a second time after an alarm to restore to Ready state. The Disarm button stays enabled when the panel is Not Ready.  
    b. Fire state take 30 seconds to clear after being cleared on the panel.  
-   All panels:  
    a. not updating when the panel arms disarms etc.  
    Subscription may have been lost during restart of web app.  
    The **AlarmDecoder UI** device renews its subscription every 5 minutes.  
    To force a renewal press **Initialize** or **Save Preferences** on the **AlarmDecoder UI** device.  
