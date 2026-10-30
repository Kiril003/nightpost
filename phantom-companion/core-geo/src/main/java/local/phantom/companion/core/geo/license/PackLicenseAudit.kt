package local.phantom.companion.core.geo.license

import local.phantom.companion.core.data.country.PackSource

/**
 * Ліцензійні ворота для геометрії, що приїхала дротом.
 *
 * `LayerCatalog` проходить [LicenseFirewall] у тесті — порушення в НАШОМУ
 * каталозі ламає збірку. Пак, завантажений з R2, збірку зламати не може: він
 * зʼявляється після неї. Тому той самий firewall стоїть тут, у рантаймі, на
 * шляху встановлення: пак, чиє джерело забороняє комерційне використання або
 * не несе обовʼязкової атрибуції, не встановлюється взагалі.
 *
 * Живе в `core-geo`, а не в `core-data`, бо `core-geo` залежить від
 * `core-data` — ребра назад нема. `core-data` бачить лише [PackSource] з
 * іменами класів рядками; enum-и і правила лишаються тут.
 */
object PackLicenseAudit {

    /** Порушення людською мовою. Порожньо — пак чистий. */
    fun violations(sources: List<PackSource>): List<String> {
        val out = mutableListOf<String>()
        val layers = mutableListOf<GeoLayer>()

        for (s in sources) {
            val license = LicenseClass.values().firstOrNull { it.name == s.license }
            val role = LayerRole.values().firstOrNull { it.name == s.role }
            if (license == null || role == null) {
                // Невідоме імʼя класу — не «мабуть, вільно»: пак назвав
                // ліцензію, якої ця збірка не знає, і оцінити її не може.
                out += "«${s.id}»: невідома ліцензія «${s.license}» або роль «${s.role}»"
                continue
            }
            layers += GeoLayer(
                id = s.id,
                title = s.title,
                source = s.source,
                license = license,
                role = role,
                attribution = s.attribution,
                joinsOsmTopology = s.joinsOsmTopology,
            )
        }

        layers.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            out += "«$it»: джерело оголошено двічі"
        }
        out += LicenseFirewall.audit(layers).map { "«${it.layerId}»: ${it.detail}" }
        return out
    }
}
