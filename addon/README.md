# BLE Studio add-on

Serves the BLE Studio web workbench through Home Assistant Ingress as a
**BLE Studio** sidebar panel. Use it to import btsnoop / JSON / CSV captures
and review them from a desktop. It requests no Bluetooth, DBus, device or
host-network privileges; the `ble_studio` custom integration performs the
explicitly enabled, device-tested GATT writes. The Android app is the
first-class capture tool.
