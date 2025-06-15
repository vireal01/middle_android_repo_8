package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.yandexpraktikum.blechat.R
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.model.Message
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelperImpl
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import ru.yandexpraktikum.blechat.utils.notifyCharUUID
import ru.yandexpraktikum.blechat.utils.serviceUUID
import java.nio.charset.Charset
import javax.inject.Inject

class BleClientControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val locationManager: LocationManager,
    private val viewModelScope: CoroutineScope,
    private val notificationsHelper: NotificationsHelperImpl,
) : BleClientController {

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    override val isLocationEnabled: StateFlow<Boolean>
        get() = _isLocationEnabled.asStateFlow()

    private var currentGatt: BluetoothGatt? = null

    init {
        updateBluetoothState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLocationState()
        }
    }

    override fun updateBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Bluetooth state", e)
        }
    }

    override fun updateLocationState() {
        try {
            _isLocationEnabled.value =
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Location state", e)
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _scannedDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            context.checkForConnectPermission {
                val bluetoothDevice = ScannedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
                _scannedDevices.update { devices ->
                    if (devices.none { it.address == bluetoothDevice.address }) {
                        devices + bluetoothDevice
                    } else devices
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE", "Scan failed with error code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server.")
                context.checkForConnectPermission {
                    gatt.discoverServices()
                    _scannedDevices.update { devices ->
                        devices.map { device ->
                            if (device.address == gatt.device.address) {
                                device.copy(isConnected = true)
                            } else {
                                device
                            }
                        }
                    }
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
                _scannedDevices.update { devices ->
                    devices.map { device ->
                        if (device.address == gatt.device.address) {
                            device.copy(isConnected = false)
                        } else {
                            device
                        }
                    }
                }
                closeConnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully.")
                val service = gatt.getService(serviceUUID)
                val notifiableCharacteristic = service?.getCharacteristic(notifyCharUUID)
                if (notifiableCharacteristic != null) {
                    context.checkForConnectPermission {
                        gatt.setCharacteristicNotification(notifiableCharacteristic, true)
                    }
                    Log.d(
                        TAG,
                        "Characteristic notification set for: ${notifiableCharacteristic.uuid}"
                    )
                } else {
                    Log.w(TAG, "Notify characteristic not found.")
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == notifyCharUUID) {
                val message = String(characteristic.value, Charset.defaultCharset())
                notificationsHelper.notifyOnMessageReceived(
                    title = context.getString(R.string.new_message),
                    message = message
                )
                viewModelScope.launch {
                    _scannedDevices.update { devices ->
                        devices.map { device ->
                            if (device.address == gatt.device.address) {
                                device.copy(
                                    messages = device.messages + Message(
                                        text = message,
                                        senderAddress = gatt.device.address,
                                        isFromLocalUser = false
                                    )
                                )
                            } else device
                        }
                    }
                }
            }
        }
    }


    override fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.startScan(scanCallback)
    }

    override fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.stopScan(scanCallback)
        _scannedDevices.update {
            it.filter { device ->
                device.isConnected
            }
        }
    }

    override fun connectToDevice(device: ScannedBluetoothDevice): Boolean {
        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        context.checkForConnectPermission {
            currentGatt = bluetoothDevice?.connectGatt(context, false, gattCallback)
        }
        return currentGatt != null
    }

    @SuppressLint("HardwareIds")
    override suspend fun sendMessage(message: String, deviceAddress: String): Boolean {
        val gatt = currentGatt ?: return false
        val service = gatt.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(notifyCharUUID)
            ?: return false

        context.checkForConnectPermission {
            characteristic.setValue(message.toByteArray(Charset.defaultCharset()))
        }
        val isSuccess = gatt.writeCharacteristic(characteristic)

        if (isSuccess) {
            _scannedDevices.update { devices ->
                devices.map { device ->
                    if (device.address == deviceAddress) {
                        device.copy(
                            messages = device.messages + Message(
                                text = message,
                                senderAddress = bluetoothAdapter?.address ?: "",
                                isFromLocalUser = true
                            )
                        )
                    } else {
                        device
                    }
                }
            }
            Log.d(TAG, "Message sent successfully: $message")
        } else {
            Log.e(TAG, "Failed to send message: $message")
        }
        return isSuccess
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun closeConnection() {
        currentGatt?.close()
        currentGatt = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun release() {
        closeConnection()
    }

    companion object {
        const val TAG = "BluetoothClientController"
    }
}