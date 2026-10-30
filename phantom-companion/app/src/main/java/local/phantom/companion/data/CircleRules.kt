package local.phantom.companion.data

object CircleRules {

    fun locationDefault(circle: String): Boolean =
        circle == "personal" || circle == "close"

    fun momentsDefault(circle: String): Boolean =
        circle == "personal" || circle == "close" || circle == "friends" || circle.startsWith("u:")

    fun canSeeLocation(circles: Set<String>): Boolean =
        circles.any(::locationDefault)

    fun canSeeMoments(circles: Set<String>): Boolean =
        circles.any(::momentsDefault)
}
