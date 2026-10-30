package local.phantom.companion.ui.messenger

/**
 * Усі двері сцени зустрічі на одному місці: заклади, бали, дотик по картці.
 *
 * Міст має ОДИН рядок на це (`if (scene.claims(method, seg)) return scene.handle(…)`),
 * а що за шлях куди веде — тут, у класі без Android. Тому тест викликає справжню
 * розвилку (метод + шлях → двері → відповідь), а не шукає літерали у вихідному
 * коді мосту: вигаданий шлях чи чужий метод не проходять, а зміна шляху в мосту
 * без зміни тут не компілюється.
 */
class WebMessengerScene(
    private val venues: WebMessengerVenues,
    private val points: WebMessengerPoints,
    private val marks: WebMessengerCardMarkDoor,
) {
    /** Чи це шлях сцени. Заклади й бали — на сервер, дотик — у дріт; до груп і людей їм діла немає. */
    fun claims(method: String, seg: List<String>): Boolean =
        WebMessengerVenues.claims(seg) || WebMessengerPoints.claims(seg) || isMark(method, seg)

    suspend fun handle(method: String, seg: List<String>, body: String?): String = when {
        WebMessengerVenues.claims(seg) -> venues.handle(method, seg, body)
        WebMessengerPoints.claims(seg) -> points.handle(method, seg, body)
        // Гілка писана буквально (сегменти й метод): за такими літералами
        // `WebBundleRoutesReachTest` дізнається, що веб може кликати цей шлях.
        seg.size == 4 && seg[1] == "messages" && seg[3] == "marks" && method == "POST" -> marks.handle(seg[2], body)
        else -> WebMessengerReply.fail(404, "not_found", "no such path")
    }

    companion object {
        /** `POST /messenger/messages/{id}/marks` (веб: `meetupApi.mark`). */
        fun isMark(method: String, seg: List<String>): Boolean =
            method == "POST" && seg.size == 4 && seg[0] == "messenger" && seg[1] == "messages" && seg[3] == "marks"
    }
}
