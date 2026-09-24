package com.example.tscprint

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

class UsbPrinter(private val context: Context) {

    private val manager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    data class Target(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val outEndpoint: UsbEndpoint
    )

    fun listDevices(): List<UsbDevice> = manager.deviceList.values.toList()

    fun findTargets(): List<Target> {
        val result = mutableListOf<Target>()
        for (device in manager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var out: UsbEndpoint? = null
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_OUT &&
                        (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT)
                    ) {
                        out = ep
                    }
                }
                if (out != null) {
                    result.add(Target(device, intf, out))
                    break
                }
            }
        }
        return result
    }

    fun findTarget(vendorId: Int, productId: Int): Target? {
        val targets = findTargets()
        if (vendorId == 0 && productId == 0) {
            val printers = targets.filter {
                it.device.deviceClass == UsbConstants.USB_CLASS_PRINTER ||
                    it.usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER
            }
            return printers.singleOrNull()
        }
        return targets.firstOrNull {
            (vendorId == 0 || it.device.vendorId == vendorId) &&
                (productId == 0 || it.device.productId == productId)
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    fun requestPermission(device: UsbDevice, action: String) {
        val intent = Intent(action).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        manager.requestPermission(device, pi)
    }

    fun send(target: Target, data: ByteArray, timeoutMs: Int = 5000): Int {
        val connection = manager.openDevice(target.device)
            ?: throw IllegalStateException("Нет доступа к USB-устройству")
        try {
            if (!connection.claimInterface(target.usbInterface, true)) {
                throw IllegalStateException("Не удалось захватить USB-интерфейс")
            }
            try {
                var offset = 0
                val chunk = 16384
                while (offset < data.size) {
                    val len = minOf(chunk, data.size - offset)
                    val slice =
                        if (offset == 0 && len == data.size) data
                        else data.copyOfRange(offset, offset + len)
                    val sent = connection.bulkTransfer(target.outEndpoint, slice, len, timeoutMs)
                    if (sent <= 0) throw IllegalStateException("bulkTransfer вернул $sent")
                    offset += sent
                }
                return offset
            } finally {
                connection.releaseInterface(target.usbInterface)
            }
        } finally {
            connection.close()
        }
    }
}
