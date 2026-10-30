package local.phantom.companion.core.vision.scene

import android.media.Image

/**
 * Кадр у тому вигляді, в якому його віддає камера: буфер плюс поворот, який
 * ще НЕ застосовано до пікселів.
 *
 * [width] і [height] — розміри вже випрямленого кадру, бо саме в них рушій
 * поверне рамки. Брати `image.width` навпростець — класичний спосіб отримати
 * накладання, розтягнуте поперек екрана.
 *
 * Кадр не закриває себе сам: власник — той, хто його дав.
 */
class VisionFrame(
    val image: Image,
    val rotationDegrees: Int,
) {
    val width: Int get() = uprightWidth(image.width, image.height, rotationDegrees)
    val height: Int get() = uprightHeight(image.width, image.height, rotationDegrees)
}
