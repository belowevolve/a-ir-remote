package belowevolve.airremote

internal enum class PcDialogPage {
    SETTINGS,
    BLUETOOTH,
    COMPANION,
    COMPANION_CODE,
}

/** Immutable history; Back reverses the full journey, not only the most recent step. */
internal data class PcDialogNavigation(val pages: List<PcDialogPage>) {
    constructor(page: PcDialogPage) : this(listOf(page))

    init {
        require(pages.isNotEmpty())
    }

    val page: PcDialogPage
        get() = pages.last()

    val canGoBack: Boolean
        get() = pages.size > 1

    fun open(page: PcDialogPage) = if (page == this.page) this else copy(pages = pages + page)

    fun back() = if (canGoBack) copy(pages = pages.dropLast(1)) else this
}
