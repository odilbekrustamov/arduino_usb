package com.cobalt.cobalt.model

import android.hardware.usb.UsbDevice
import com.hoho.android.usbserial.driver.UsbSerialDriver

data class ListItem(
    var device: UsbDevice,
    var port: Int,
    var driver: UsbSerialDriver?,
)