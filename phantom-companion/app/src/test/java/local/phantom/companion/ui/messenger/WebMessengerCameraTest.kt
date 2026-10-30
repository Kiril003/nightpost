package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сканування QR — єдиний спосіб додати людину, не набираючи рядок руками.
 *
 * До 06.09 воно було зачинене для КОЖНОГО, хто ще нічого не налаштував:
 * `onPermissionRequest` віддавав `deny`, доки дозволу на камеру ще немає, а
 * коментар поруч пояснював, що дозвіл «дає рідний сканер». Але застосунок
 * стартує одразу у веб-месенджер — до рідного сканера свіже встановлення не
 * проходить ніколи. Тобто двері були намальовані й замкнені за побудовою.
 *
 * **Чому це не побачили раніше.** На робочому апараті `CAMERA: granted=true`
 * з давніх прогонів: там QR працює, кадр виходить зелений, і вада лишається
 * рівно в тих, у кого телефон новий. Перевірити це поведінкою на своєму
 * пристрої неможливо — тому політика винесена окремою функцією й доводиться
 * тут, а не оком.
 *
 * Тест стереже ОБИДВІ половини: що ми питаємо там, де раніше мовчали, і що
 * жодна з меж безпеки при цьому не поїхала.
 */
class WebMessengerCameraTest {

    @Test
    fun `без дозволу тепер ПИТАЄМО, а не відмовляємо мовчки`() {
        assertEquals(
            "свіже встановлення: сканер мусить викликати вікно запиту",
            CameraAsk.ASK,
            cameraVerdict(ours = true, cameraOnly = true, held = false, alreadyAsking = false),
        )
    }

    @Test
    fun `дозвіл уже є — віддаємо одразу, без зайвого вікна`() {
        assertEquals(
            CameraAsk.GRANT,
            cameraVerdict(ours = true, cameraOnly = true, held = true, alreadyAsking = false),
        )
    }

    @Test
    fun `чуже походження не дістає камери навіть із дозволом`() {
        assertEquals(
            "камера — лише нашому бандлу",
            CameraAsk.DENY,
            cameraVerdict(ours = false, cameraOnly = true, held = true, alreadyAsking = false),
        )
        assertEquals(
            CameraAsk.DENY,
            cameraVerdict(ours = false, cameraOnly = true, held = false, alreadyAsking = false),
        )
    }

    @Test
    fun `не лише відео — відмова`() {
        // Прохання про мікрофон разом із камерою мусить упасть цілком:
        // «усі ресурси — відео» лишається умовою, а не побажанням.
        assertEquals(
            CameraAsk.DENY,
            cameraVerdict(ours = true, cameraOnly = false, held = true, alreadyAsking = false),
        )
    }

    @Test
    fun `друге прохання під час відкритого вікна — відмова, а не другий запит`() {
        // Інакше перший PermissionRequest не дочекався б відповіді ніколи, і
        // сканер лишився б чорним прямокутником без пояснення.
        assertEquals(
            CameraAsk.DENY,
            cameraVerdict(ours = true, cameraOnly = true, held = false, alreadyAsking = true),
        )
    }

    @Test
    fun `mediaVerdict — дзвінки питають дозвіл коли його ще немає`() {
        assertEquals(
            MediaAsk.ASK,
            mediaVerdict(ours = true, validResources = true, allHeld = false, alreadyAsking = false),
        )
    }

    @Test
    fun `mediaVerdict — дозволи вже є віддаємо одразу`() {
        assertEquals(
            MediaAsk.GRANT,
            mediaVerdict(ours = true, validResources = true, allHeld = true, alreadyAsking = false),
        )
    }

    @Test
    fun `mediaVerdict — чуже походження або невідомі ресурси отримують відмову`() {
        assertEquals(
            MediaAsk.DENY,
            mediaVerdict(ours = false, validResources = true, allHeld = true, alreadyAsking = false),
        )
        assertEquals(
            MediaAsk.DENY,
            mediaVerdict(ours = true, validResources = false, allHeld = true, alreadyAsking = false),
        )
    }
}
