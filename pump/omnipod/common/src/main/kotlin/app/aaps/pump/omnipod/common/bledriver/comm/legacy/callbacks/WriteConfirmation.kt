<<<<<<<< HEAD:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/callbacks/WriteConfirmation.kt
package app.aaps.pump.omnipod.common.bledriver.comm.callbacks
========
package app.aaps.pump.omnipod.common.bledriver.comm.legacy.callbacks
>>>>>>>> 3.4.2.1:pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/legacy/callbacks/WriteConfirmation.kt

sealed class WriteConfirmation

data class WriteConfirmationSuccess(val uuid: String, val payload: ByteArray) : WriteConfirmation()

data class WriteConfirmationError(
    val msg: String,
    val status: Int = 0
) : WriteConfirmation()
