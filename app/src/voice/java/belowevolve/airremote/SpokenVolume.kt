package belowevolve.airremote

internal object SpokenVolume {
    private val small = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
        "семнадцать", "восемнадцать", "девятнадцать")
    private val tens = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")

    fun words(value: Int): String {
        require(value in 0..100)
        return when {
            value < 20 -> small[value]
            value == 100 -> "сто"
            value % 10 == 0 -> tens[value / 10]
            else -> "${tens[value / 10]} ${small[value % 10]}"
        }
    }

    private val values by lazy { (0..100).associateBy(::words) }
    fun parse(text: String): Int? = values[text] ?: text.toIntOrNull()?.takeIf { it in 0..100 }
}
