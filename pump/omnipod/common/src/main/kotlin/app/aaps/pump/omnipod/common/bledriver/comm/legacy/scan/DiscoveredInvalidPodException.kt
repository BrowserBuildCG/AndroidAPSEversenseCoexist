<<<<<<<< HEAD:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/scan/DiscoveredInvalidPodException.kt
package app.aaps.pump.omnipod.common.bledriver.comm.scan
========
package app.aaps.pump.omnipod.common.bledriver.comm.legacy.scan
>>>>>>>> 3.4.2.1:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/legacy/scan/DiscoveredInvalidPodException.kt

import android.os.ParcelUuid

class DiscoveredInvalidPodException(message: String, serviceUUIds: List<ParcelUuid?>) :
    Exception("$message service UUIDs: $serviceUUIds")
