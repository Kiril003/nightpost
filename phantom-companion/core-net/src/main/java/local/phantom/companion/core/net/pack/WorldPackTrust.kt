package local.phantom.companion.core.net.pack

import local.phantom.companion.core.license.SignedDocument

/**
 * Межа довіри світових пакiв — основи мапи й дорожнього графа.
 *
 * До 07.09 її не було зовсім: [WorldPackGate.inspect] звіряв розмір і форму
 * рядка sha256, узятих із ТОГО САМОГО документа, який і перевірявся. Манiфест,
 * що сам собі оголошує суму власного файла, доводить рівно одне — що дзеркало
 * вміє рахувати sha256. Будь-яке дзеркало могло підмінити дорожній граф разом
 * із самоузгодженим манiфестом, і телефон прийняв би це як своє.
 *
 * Криптографії тут не винаходять: той самий [SignedDocument], яким живуть
 * ліцензійний сертифікат, політика збірки і паки країн — Ed25519 над
 * канонічними байтами документа (ключі відсортовані на кожному рівні, поле
 * `sig` викинуте), публічний ключ шістнадцятковим рядком зі збірки. Приватний
 * ключ на пристрої не буває ніколи: клієнт уміє лише перевіряти.
 *
 * ПОРОЖНІЙ ключ означає «не приймається жоден завантажений пакет», а не
 * «приймаються всі» — та сама постава, що й `LICENSE_PUBLIC_KEY=""`.
 */
object WorldPackTrust {

    /** Чому документу не вірять. Мовчазної відмови не буває. */
    enum class Fault { NO_ANCHOR, MALFORMED, NOT_SIGNED, BAD_SIGNATURE }

    fun fault(text: String, publicKeyHex: String): Fault? {
        if (publicKeyHex.isBlank()) return Fault.NO_ANCHOR
        val document = SignedDocument.parse(text) ?: return Fault.MALFORMED
        if (!document.containsKey("sig")) return Fault.NOT_SIGNED
        return if (SignedDocument.verify(document, publicKeyHex)) null else Fault.BAD_SIGNATURE
    }
}
