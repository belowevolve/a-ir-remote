package belowevolve.airremote

internal fun pcKeyLabel(label: String, russian: Boolean, shift: Boolean, capsLock: Boolean): String {
    if (label.length != 1) return label
    val latin = "`qwertyuiop[]asdfghjkl;'zxcvbnm,."
    val cyrillic = "ёйцукенгшщзхъфывапролджэячсмитьбю"
    val index = latin.indexOf(label)
    val localized = if (russian && index >= 0) cyrillic[index] else label.single()
    if (localized.isLetter()) return if (shift xor capsLock) localized.uppercaseChar().toString() else localized.toString()
    if (russian && label == "/") return if (shift) "," else "."
    if (!shift) return localized.toString()
    val plain = "`1234567890-=[]\\;',./"
    val shifted = if (russian) "Ё!\"№;%:?*()_+ХЪ/ЖЭБЮ," else "~!@#$%^&*()_+{}|:\"<>?"
    val position = plain.indexOf(label)
    return if (position >= 0) shifted[position].toString() else label
}
