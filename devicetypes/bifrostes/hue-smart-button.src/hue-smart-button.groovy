/**
 *  Philips HUE Button 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.json.JsonOutput
import physicalgraph.zigbee.zcl.DataType

metadata {
    definition (name: "HUE Smart Button", namespace: "bifrostes", author: "Marco van Es", runLocally: true, executeCommandsLocally: false, vid: "generic-button", ocfDeviceType: "x.com.st.d.remotecontroller") {
        capability "Actuator"
        capability "Battery"
        capability "Button"
        capability "Holdable Button"        
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "Health Check"
        fingerprint inClusters: "0000,0001,0003,FC00,1000", outClusters: "0019", manufacturer: "Philips", model: "ROM001", deviceJoinName: "Hue Smart button"
    }

    simulator {}

    tiles {
        standardTile("button", "device.button", width: 2, height: 2) {
            state "default", label: "", icon: "st.switches.switch.off", backgroundColor: "#ffffff"
            state "button pushed", label: "pushed", icon: "st.switches.switch.on", backgroundColor: "#00A0DC"
        }

        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false) {
            state "battery", label:'${currentValue}% battery', unit:""
        }

        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat") {
            state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        main (["button"])
        details(["button", "battery", "refresh"])
    }
}

def parse(String description) {
    log.debug "description = $description"
    def event = zigbee.getEvent(description)
    if (event) {
        sendEvent(event)
    }
    else {
        if ((description?.startsWith("catchall:")) || (description?.startsWith("read attr -"))) {
            def descMap = zigbee.parseDescriptionAsMap(description)
            if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0020 && descMap.value != null) {
                event = getBatteryResult(zigbee.convertHexToInt(descMap.value))
            }
            else if (descMap.clusterInt == 0x0006 || descMap.clusterInt == 0x0008) {
                event = parseNonIasButtonMessage(descMap)
            }
        }
        else if (description?.startsWith('zone status')) {
            event = parseIasButtonMessage(description)
        }

        log.debug "Parse returned $event"
        def result = event ? createEvent(event) : []

        if (description?.startsWith('enroll request')) {
            List cmds = zigbee.enrollResponse()
            result = cmds?.collect { new physicalgraph.device.HubAction(it) }
        }
        return result
    }
}

private Map parseIasButtonMessage(String description) {
    log.debug "parseIasButtonMessage = $description"
    def zs = zigbee.parseZoneStatus(description)
    return zs.isAlarm2Set() ? getButtonResult("press") : getButtonResult("release")
}

private Map getBatteryResult(rawValue) {
    log.debug 'Battery'
    def volts = rawValue / 10
    if (volts > 3.0 || volts == 0 || rawValue == 0xFF) {
        return [:]
    }
    else {
        def result = [
                name: 'battery'
        ]
        def minVolts = 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        result.value = Math.min(100, (int)(pct * 100))
        def linkText = getLinkText(device)
        result.descriptionText = "${linkText} battery was ${result.value}%"
        return result
    }
}

private Map parseNonIasButtonMessage(Map descMap){
    log.debug "parseNonIasButtonMessage descMap: commandInt = $descMap.commandInt, clusterInt = $descMap.clusterInt, command = $descMap.command"
    def buttonState = ""
    def buttonNumber = 0
    if (descMap.clusterInt == 0x0006) {
        buttonState = "pushed"
        if ((descMap.command == "01") || (descMap.command == '40')) { //Added 40 to support HUE button second press
            buttonNumber = 1
        }
        if (buttonNumber !=0) {
            def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
            return createEvent(name: "button", value: buttonState, data: [buttonNumber: buttonNumber], descriptionText: descriptionText, isStateChange: true)
        }
        else {
            return [:]
        }
    }
    else if (descMap.clusterInt == 0x0008) {
        if (descMap.command == "02") {
            buttonState = "held"
            state.buttonNumber = 1
            buttonNumber = state.buttonNumber
            def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
            return createEvent(name: "button", value: buttonState, data: [buttonNumber: buttonNumber], descriptionText: descriptionText, isStateChange: true)
        }
    }
    log.debug "Button state = $buttonState"
}

def refresh() {
    log.debug "Refreshing Battery"
    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20) +
            zigbee.enrollResponse()
}

def configure() {
    log.debug "Configuring Reporting, IAS CIE, and Bindings."
    def cmds = []
    return zigbee.onOffConfig() +
            zigbee.levelConfig() +
            zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20, DataType.UINT8, 30, 21600, 0x01) +
            zigbee.enrollResponse() +
            zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20) +
            cmds

}

private Map getButtonResult(buttonState, buttonNumber = 1) {
    log.debug "Button was value : $buttonState"
    buttonState = "held"
    def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
    return createEvent(name: "button", value: buttonState, data: [buttonNumber: buttonNumber], descriptionText: descriptionText, isStateChange: true)
}

def installed() {
    initialize()
    sendEvent(name: "supportedButtonValues", value: ["pushed","held"].encodeAsJSON(), displayed: false)
    sendEvent(name: "numberOfButtons", value: 1, displayed: false)
    sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], displayed: false)
}

def updated() {
    initialize()
}

def initialize() {
    sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)
    sendEvent(name: "numberOfButtons", value: 1, displayed: false)
}
