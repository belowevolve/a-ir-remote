package belowevolve.airremote

/** The visible TV flow. Network connectivity and the saved certificate remain independent. */
internal enum class TvDeviceDialogState {
    CLOSED,
    DEVICES,
    CONNECTING,
    CODE,
    VERIFYING,
    SETTINGS;

    val busy: Boolean
        get() = this == CONNECTING || this == VERIFYING

    val expectsCode: Boolean
        get() = this == CODE || this == VERIFYING

}
