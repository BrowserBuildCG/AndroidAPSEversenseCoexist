<<<<<<<< HEAD:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/io/DataBleIO.kt
package app.aaps.pump.omnipod.common.bledriver.comm.io
========
package app.aaps.pump.omnipod.common.bledriver.comm.legacy.io
>>>>>>>> 3.4.2.1:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/legacy/io/DataBleIO.kt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import app.aaps.core.interfaces.logging.AAPSLogger
<<<<<<<< HEAD:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/io/DataBleIO.kt
import app.aaps.pump.omnipod.common.bledriver.comm.callbacks.BleCommCallbacks
========
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.DataBleIO as DataBleIOInterface
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.CharacteristicType
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.callbacks.BleCommCallbacks
>>>>>>>> 3.4.2.1:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/legacy/io/DataBleIO.kt
import java.util.concurrent.BlockingQueue

class DataBleIO(
    logger: AAPSLogger,
    characteristic: BluetoothGattCharacteristic,
    incomingPackets: BlockingQueue<ByteArray>,
    gatt: BluetoothGatt,
    bleCommCallbacks: BleCommCallbacks
) : BleIO(
    logger,
    characteristic,
    incomingPackets,
    gatt,
    bleCommCallbacks,
    CharacteristicType.DATA
), DataBleIOInterface
