package belowevolve.airremote

import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

internal fun tvConnectionError(error: Exception): String = when {
    generateSequence<Throwable>(error) { it.cause }.any {
        it is CertificateException || it is SSLPeerUnverifiedException ||
            it.message?.contains("certificate", ignoreCase = true) == true ||
            it.message?.contains("Сертификат ТВ изменился") == true
    } -> "ТВ отклонил соединение. Проверьте сопряжение"
    else -> "ТВ недоступен. Проверьте питание и Wi-Fi"
}
