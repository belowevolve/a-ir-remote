package belowevolve.airremote

object IrSignal {
    fun haierPower(): String {
        val durations = mutableListOf(9000, 4500)
        for (byte in listOf(0x04, 0xfb, 0x08, 0xf7)) {
            repeat(8) { bit ->
                durations.add(560)
                durations.add(if ((byte and (1 shl bit)) != 0) 1690 else 560)
            }
        }
        durations.add(560)
        return "38000 " + durations.joinToString(" ")
    }

    fun parse(value: String): Pair<Int, IntArray> {
        val numbers = value.trim().split(Regex("[\\s,;]+")).map {
            requireNotNull(it.toIntOrNull()) { "Нужны целые числа: частота и длительности" }
        }
        require(numbers.size >= 4) { "Нужны частота и как минимум три длительности" }
        require(numbers[0] in (20000..60000)) { "Частота должна быть от 20000 до 60000 Гц" }
        val pattern = numbers.drop(1).toIntArray()
        require((pattern.size <= 2000) && (pattern.all { it in (1..100000) })) { "Неверные длительности ИК-сигнала" }
        require(pattern.sumOf { it.toLong() } <= 2000000) { "Сигнал должен быть короче 2 секунд" }
        return numbers[0] to pattern
    }
}
