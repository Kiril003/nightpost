package local.phantom.companion.core.truth

/**
 * Порт `latLngToCell` з H3 (Uber, Apache-2.0). Сервер правди приймає лише
 * комірку r8 і сам її не рахує, тож комірка телефона мусить збігатися з h3-js
 * біт у біт — це стережуть вектори `h3_vectors.json` (scripts/h3-vectors.cjs).
 */
object H3 {
    const val CELL_RES = 8
    const val AREA_RES = 6

    fun cell(latDeg: Double, lngDeg: Double): CellId = CellId(latLngToCell(latDeg, lngDeg, CELL_RES))

    fun area(cell: CellId): String = parent(cell.value, AREA_RES)

    fun latLngToCell(latDeg: Double, lngDeg: Double, res: Int): String {
        require(res in 0..15 && latDeg.isFinite() && lngDeg.isFinite())
        // Саме так, як h3-js: `deg * PI / 180`, а не Math.toRadians — інше округлення.
        val lat = latDeg * Math.PI / 180
        val lng = lngDeg * Math.PI / 180
        val face = closestFace(lat, lng)
        val (x, y) = hex2d(lat, lng, face, res)
        val ijk = hex2dToIjk(x, y)
        return java.lang.Long.toHexString(faceIjkToH3(face, ijk, res))
    }

    fun parent(cell: String, res: Int): String {
        var h = java.lang.Long.parseUnsignedLong(cell, 16)
        val own = resOf(h)
        require(res in 0..own)
        h = (h and (0xFL shl 52).inv()) or (res.toLong() shl 52)
        for (r in res + 1..own) h = setDigit(h, r, 7)
        return java.lang.Long.toHexString(h)
    }

    private const val EPSILON = 0.0000000000000001
    private const val M_2PI = 6.28318530717958647692528676655900576839433
    private const val M_AP7_ROT_RADS = 0.333473172251832115336090755351601070065900389
    private const val RES0_U_GNOMONIC = 0.38196601125010500003
    private const val M_SQRT7 = 2.6457513110645905905016157536392604257102
    private const val M_RSIN60 = 1.1547005383792515290182975610039149112953
    private const val H3_INIT = 35184372088831L
    private const val K_AXES_DIGIT = 1

    private val faceCenterGeo: DoubleArray by lazy {
        DoubleArray(40).also { g ->
            for (f in 0 until 20) {
                g[f * 2] = StrictMath.asin(FACE_CENTER_POINT[f * 3 + 2])
                g[f * 2 + 1] = StrictMath.atan2(FACE_CENTER_POINT[f * 3 + 1], FACE_CENTER_POINT[f * 3])
            }
        }
    }

    private fun sqdToFace(lat: Double, lng: Double, f: Int): Double {
        val r = StrictMath.cos(lat)
        val dx = FACE_CENTER_POINT[f * 3] - StrictMath.cos(lng) * r
        val dy = FACE_CENTER_POINT[f * 3 + 1] - StrictMath.sin(lng) * r
        val dz = FACE_CENTER_POINT[f * 3 + 2] - StrictMath.sin(lat)
        return dx * dx + dy * dy + dz * dz
    }

    private fun closestFace(lat: Double, lng: Double): Int {
        var best = 0
        var sqd = 5.0
        for (f in 0 until 20) {
            val d = sqdToFace(lat, lng, f)
            if (d < sqd) { best = f; sqd = d }
        }
        return best
    }

    private fun posAngle(a: Double): Double {
        var t = if (a < 0.0) a + M_2PI else a
        if (a >= M_2PI) t -= M_2PI
        return t
    }

    private fun hex2d(lat: Double, lng: Double, face: Int, res: Int): Pair<Double, Double> {
        var r = StrictMath.acos(1 - sqdToFace(lat, lng, face) / 2)
        if (r < EPSILON) return 0.0 to 0.0
        val g = faceCenterGeo
        val cLat = g[face * 2]; val cLng = g[face * 2 + 1]
        val az = StrictMath.atan2(
            StrictMath.cos(lat) * StrictMath.sin(lng - cLng),
            StrictMath.cos(cLat) * StrictMath.sin(lat) - StrictMath.sin(cLat) * StrictMath.cos(lat) * StrictMath.cos(lng - cLng),
        )
        var theta = posAngle(FACE_AXES_AZ_CII[face * 3] - posAngle(az))
        if (res % 2 == 1) theta = posAngle(theta - M_AP7_ROT_RADS)
        r = StrictMath.tan(r) / RES0_U_GNOMONIC
        for (i in 0 until res) r *= M_SQRT7
        return r * StrictMath.cos(theta) to r * StrictMath.sin(theta)
    }

    private fun hex2dToIjk(vx: Double, vy: Double): IntArray {
        val a1 = Math.abs(vx)
        val a2 = Math.abs(vy)
        val x2 = a2 * M_RSIN60
        val x1 = a1 + x2 / 2.0
        val m1 = x1.toInt()
        val m2 = x2.toInt()
        val r1 = x1 - m1
        val r2 = x2 - m2
        var i: Int
        var j: Int
        if (r1 < 0.5) {
            if (r1 < 1.0 / 3.0) {
                i = m1
                j = if (r2 < (1.0 + r1) / 2.0) m2 else m2 + 1
            } else {
                j = if (r2 < 1.0 - r1) m2 else m2 + 1
                i = if (1.0 - r1 <= r2 && r2 < 2.0 * r1) m1 + 1 else m1
            }
        } else {
            if (r1 < 2.0 / 3.0) {
                j = if (r2 < 1.0 - r1) m2 else m2 + 1
                i = if (2.0 * r1 - 1.0 < r2 && r2 < 1.0 - r1) m1 else m1 + 1
            } else {
                i = m1 + 1
                j = if (r2 < r1 / 2.0) m2 else m2 + 1
            }
        }
        if (vx < 0.0) {
            i = if (j % 2 == 0) (i - 2.0 * (i - j / 2)).toInt() else (i - (2.0 * (i - (j + 1) / 2) + 1)).toInt()
        }
        if (vy < 0.0) {
            i -= (2 * j + 1) / 2
            j = -j
        }
        return normalize(intArrayOf(i, j, 0))
    }

    private fun normalize(c: IntArray): IntArray {
        if (c[0] < 0) { c[1] -= c[0]; c[2] -= c[0]; c[0] = 0 }
        if (c[1] < 0) { c[0] -= c[1]; c[2] -= c[1]; c[1] = 0 }
        if (c[2] < 0) { c[0] -= c[2]; c[1] -= c[2]; c[2] = 0 }
        val min = minOf(c[0], c[1], c[2])
        if (min > 0) { c[0] -= min; c[1] -= min; c[2] -= min }
        return c
    }

    // lroundl: половина — від нуля, а не вгору, як Math.round.
    private fun lround(x: Double): Int = if (x >= 0) Math.floor(x + 0.5).toInt() else -Math.floor(-x + 0.5).toInt()

    private fun up(c: IntArray, classIII: Boolean) {
        val i = c[0] - c[2]
        val j = c[1] - c[2]
        if (classIII) { c[0] = lround((3 * i - j) / 7.0); c[1] = lround((i + 2 * j) / 7.0) }
        else { c[0] = lround((2 * i + j) / 7.0); c[1] = lround((3 * j - i) / 7.0) }
        c[2] = 0
        normalize(c)
    }

    private fun down(c: IntArray, classIII: Boolean): IntArray {
        val v = if (classIII) intArrayOf(3, 0, 1, 1, 3, 0, 0, 1, 3) else intArrayOf(3, 1, 0, 0, 3, 1, 1, 0, 3)
        val out = IntArray(3)
        for (axis in 0 until 3) for (n in 0 until 3) out[n] += v[axis * 3 + n] * c[axis]
        return normalize(out)
    }

    private val UNIT_VECS = intArrayOf(0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 1, 1, 0, 0, 1, 0, 1, 1, 1, 0)

    private fun unitDigit(c: IntArray): Int {
        normalize(c)
        for (d in 0 until 7) if (UNIT_VECS[d * 3] == c[0] && UNIT_VECS[d * 3 + 1] == c[1] && UNIT_VECS[d * 3 + 2] == c[2]) return d
        return 7
    }

    private fun faceIjkToH3(face: Int, ijk: IntArray, res: Int): Long {
        var h = (H3_INIT and (0xFL shl 59).inv()) or (1L shl 59)
        h = (h and (0xFL shl 52).inv()) or (res.toLong() shl 52)
        for (r in res - 1 downTo 0) {
            val last = ijk.copyOf()
            val classIII = (r + 1) % 2 == 1
            up(ijk, classIII)
            val center = down(ijk.copyOf(), classIII)
            h = setDigit(h, r + 1, unitDigit(intArrayOf(last[0] - center[0], last[1] - center[1], last[2] - center[2])))
        }
        require(ijk[0] <= 2 && ijk[1] <= 2 && ijk[2] <= 2) { "поза гранню" }
        val slot = (((face * 3 + ijk[0]) * 3 + ijk[1]) * 3 + ijk[2]) * 2
        val baseCell = FACE_IJK_BASE_CELLS[slot]
        val rots = FACE_IJK_BASE_CELLS[slot + 1]
        h = (h and (0x7FL shl 45).inv()) or (baseCell.toLong() shl 45)
        if (BASE_CELL_DATA[baseCell * 7 + 4] == 1) {
            if (leadingNonZero(h) == K_AXES_DIGIT) {
                val cwOffset = BASE_CELL_DATA[baseCell * 7 + 5] == face || BASE_CELL_DATA[baseCell * 7 + 6] == face
                h = if (cwOffset) rotate(h, CW) else rotate(h, CCW)
            }
            repeat(rots) { h = rotatePentCcw(h) }
        } else {
            repeat(rots) { h = rotate(h, CCW) }
        }
        return h
    }

    private val CCW = intArrayOf(0, 5, 3, 1, 6, 4, 2, 7)
    private val CW = intArrayOf(0, 3, 6, 2, 5, 1, 4, 7)

    private fun resOf(h: Long): Int = ((h ushr 52) and 0xF).toInt()

    private fun digit(h: Long, r: Int): Int = ((h ushr ((15 - r) * 3)) and 7).toInt()

    private fun setDigit(h: Long, r: Int, d: Int): Long {
        val shift = (15 - r) * 3
        return (h and (7L shl shift).inv()) or (d.toLong() shl shift)
    }

    private fun leadingNonZero(h: Long): Int {
        for (r in 1..resOf(h)) digit(h, r).let { if (it != 0) return it }
        return 0
    }

    private fun rotate(h: Long, table: IntArray): Long {
        var out = h
        for (r in 1..resOf(h)) out = setDigit(out, r, table[digit(out, r)])
        return out
    }

    private fun rotatePentCcw(h: Long): Long {
        var out = h
        var found = false
        for (r in 1..resOf(out)) {
            out = setDigit(out, r, CCW[digit(out, r)])
            if (!found && digit(out, r) != 0) {
                found = true
                if (leadingNonZero(out) == K_AXES_DIGIT) out = rotate(out, CCW)
            }
        }
        return out
    }

    // Номер базової комірки й поворот для (грань, i, j, k) на роздільності 0: [20][3][3][3] × 2.
    private val FACE_IJK_BASE_CELLS = intArrayOf(
        16, 0, 18, 0, 24, 0, 33, 0, 30, 0, 32, 3, 49, 1, 48, 3, 50, 3,
        8, 0, 5, 5, 10, 5, 22, 0, 16, 0, 18, 0, 41, 1, 33, 0, 30, 0,
        4, 0, 0, 5, 2, 5, 15, 1, 8, 0, 5, 5, 31, 1, 22, 0, 16, 0,
        2, 0, 6, 0, 14, 0, 10, 0, 11, 0, 17, 3, 24, 1, 23, 3, 25, 3,
        0, 0, 1, 5, 9, 5, 5, 0, 2, 0, 6, 0, 18, 1, 10, 0, 11, 0,
        4, 1, 3, 5, 7, 5, 8, 1, 0, 0, 1, 5, 16, 1, 5, 0, 2, 0,
        7, 0, 21, 0, 38, 0, 9, 0, 19, 0, 34, 3, 14, 1, 20, 3, 36, 3,
        3, 0, 13, 5, 29, 5, 1, 0, 7, 0, 21, 0, 6, 1, 9, 0, 19, 0,
        4, 2, 12, 5, 26, 5, 0, 1, 3, 0, 13, 5, 2, 1, 1, 0, 7, 0,
        26, 0, 42, 0, 58, 0, 29, 0, 43, 0, 62, 3, 38, 1, 47, 3, 64, 3,
        12, 0, 28, 5, 44, 5, 13, 0, 26, 0, 42, 0, 21, 1, 29, 0, 43, 0,
        4, 3, 15, 5, 31, 5, 3, 1, 12, 0, 28, 5, 7, 1, 13, 0, 26, 0,
        31, 0, 41, 0, 49, 0, 44, 0, 53, 0, 61, 3, 58, 1, 65, 3, 75, 3,
        15, 0, 22, 5, 33, 5, 28, 0, 31, 0, 41, 0, 42, 1, 44, 0, 53, 0,
        4, 4, 8, 5, 16, 5, 12, 1, 15, 0, 22, 5, 26, 1, 28, 0, 31, 0,
        50, 0, 48, 0, 49, 3, 32, 0, 30, 3, 33, 3, 24, 3, 18, 3, 16, 3,
        70, 0, 67, 0, 66, 3, 52, 3, 50, 0, 48, 0, 37, 3, 32, 0, 30, 3,
        83, 0, 87, 3, 85, 3, 74, 3, 70, 0, 67, 0, 57, 1, 52, 3, 50, 0,
        25, 0, 23, 0, 24, 3, 17, 0, 11, 3, 10, 3, 14, 3, 6, 3, 2, 3,
        45, 0, 39, 0, 37, 3, 35, 3, 25, 0, 23, 0, 27, 3, 17, 0, 11, 3,
        63, 0, 59, 3, 57, 3, 56, 3, 45, 0, 39, 0, 46, 3, 35, 3, 25, 0,
        36, 0, 20, 0, 14, 3, 34, 0, 19, 3, 9, 3, 38, 3, 21, 3, 7, 3,
        55, 0, 40, 0, 27, 3, 54, 3, 36, 0, 20, 0, 51, 3, 34, 0, 19, 3,
        72, 0, 60, 3, 46, 3, 73, 3, 55, 0, 40, 0, 71, 3, 54, 3, 36, 0,
        64, 0, 47, 0, 38, 3, 62, 0, 43, 3, 29, 3, 58, 3, 42, 3, 26, 3,
        84, 0, 69, 0, 51, 3, 82, 3, 64, 0, 47, 0, 76, 3, 62, 0, 43, 3,
        97, 0, 89, 3, 71, 3, 98, 3, 84, 0, 69, 0, 96, 3, 82, 3, 64, 0,
        75, 0, 65, 0, 58, 3, 61, 0, 53, 3, 44, 3, 49, 3, 41, 3, 31, 3,
        94, 0, 86, 0, 76, 3, 81, 3, 75, 0, 65, 0, 66, 3, 61, 0, 53, 3,
        107, 0, 104, 3, 96, 3, 101, 3, 94, 0, 86, 0, 85, 3, 81, 3, 75, 0,
        57, 0, 59, 0, 63, 3, 74, 0, 78, 3, 79, 3, 83, 3, 92, 3, 95, 3,
        37, 0, 39, 3, 45, 3, 52, 0, 57, 0, 59, 0, 70, 3, 74, 0, 78, 3,
        24, 0, 23, 3, 25, 3, 32, 3, 37, 0, 39, 3, 50, 3, 52, 0, 57, 0,
        46, 0, 60, 0, 72, 3, 56, 0, 68, 3, 80, 3, 63, 3, 77, 3, 90, 3,
        27, 0, 40, 3, 55, 3, 35, 0, 46, 0, 60, 0, 45, 3, 56, 0, 68, 3,
        14, 0, 20, 3, 36, 3, 17, 3, 27, 0, 40, 3, 25, 3, 35, 0, 46, 0,
        71, 0, 89, 0, 97, 3, 73, 0, 91, 3, 103, 3, 72, 3, 88, 3, 105, 3,
        51, 0, 69, 3, 84, 3, 54, 0, 71, 0, 89, 0, 55, 3, 73, 0, 91, 3,
        38, 0, 47, 3, 64, 3, 34, 3, 51, 0, 69, 3, 36, 3, 54, 0, 71, 0,
        96, 0, 104, 0, 107, 3, 98, 0, 110, 3, 115, 3, 97, 3, 111, 3, 119, 3,
        76, 0, 86, 3, 94, 3, 82, 0, 96, 0, 104, 0, 84, 3, 98, 0, 110, 3,
        58, 0, 65, 3, 75, 3, 62, 3, 76, 0, 86, 3, 64, 3, 82, 0, 96, 0,
        85, 0, 87, 0, 83, 3, 101, 0, 102, 3, 100, 3, 107, 3, 112, 3, 114, 3,
        66, 0, 67, 3, 70, 3, 81, 0, 85, 0, 87, 0, 94, 3, 101, 0, 102, 3,
        49, 0, 48, 3, 50, 3, 61, 3, 66, 0, 67, 3, 75, 3, 81, 0, 85, 0,
        95, 0, 92, 0, 83, 0, 79, 0, 78, 0, 74, 3, 63, 1, 59, 3, 57, 3,
        109, 0, 108, 0, 100, 5, 93, 1, 95, 0, 92, 0, 77, 1, 79, 0, 78, 0,
        117, 4, 118, 5, 114, 5, 106, 1, 109, 0, 108, 0, 90, 1, 93, 1, 95, 0,
        90, 0, 77, 0, 63, 0, 80, 0, 68, 0, 56, 3, 72, 1, 60, 3, 46, 3,
        106, 0, 93, 0, 79, 5, 99, 1, 90, 0, 77, 0, 88, 1, 80, 0, 68, 0,
        117, 3, 109, 5, 95, 5, 113, 1, 106, 0, 93, 0, 105, 1, 99, 1, 90, 0,
        105, 0, 88, 0, 72, 0, 103, 0, 91, 0, 73, 3, 97, 1, 89, 3, 71, 3,
        113, 0, 99, 0, 80, 5, 116, 1, 105, 0, 88, 0, 111, 1, 103, 0, 91, 0,
        117, 2, 106, 5, 90, 5, 121, 1, 113, 0, 99, 0, 119, 1, 116, 1, 105, 0,
        119, 0, 111, 0, 97, 0, 115, 0, 110, 0, 98, 3, 107, 1, 104, 3, 96, 3,
        121, 0, 116, 0, 103, 5, 120, 1, 119, 0, 111, 0, 112, 1, 115, 0, 110, 0,
        117, 1, 113, 5, 105, 5, 118, 1, 121, 0, 116, 0, 114, 1, 120, 1, 119, 0,
        114, 0, 112, 0, 107, 0, 100, 0, 102, 0, 101, 3, 83, 1, 87, 3, 85, 3,
        118, 0, 120, 0, 115, 5, 108, 1, 114, 0, 112, 0, 92, 1, 100, 0, 102, 0,
        117, 0, 121, 5, 119, 5, 109, 1, 118, 0, 120, 0, 95, 1, 108, 1, 114, 0,
    )

    // Базова комірка: грань і ijk дому, пʼятикутник, дві грані зі зсувом за годинниковою.
    private val BASE_CELL_DATA = intArrayOf(
        1, 1, 0, 0, 0, 0, 0, 2, 1, 1, 0, 0, 0, 0,
        1, 0, 0, 0, 0, 0, 0, 2, 1, 0, 0, 0, 0, 0,
        0, 2, 0, 0, 1, -1, -1, 1, 1, 1, 0, 0, 0, 0,
        1, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0,
        0, 1, 0, 0, 0, 0, 0, 2, 0, 1, 0, 0, 0, 0,
        1, 0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 0, 0,
        3, 1, 0, 0, 0, 0, 0, 3, 1, 1, 0, 0, 0, 0,
        11, 2, 0, 0, 1, 2, 6, 4, 1, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 6, 0, 1, 0, 0, 0, 0,
        0, 0, 0, 1, 0, 0, 0, 2, 0, 1, 1, 0, 0, 0,
        7, 0, 0, 1, 0, 0, 0, 2, 0, 0, 1, 0, 0, 0,
        0, 1, 1, 0, 0, 0, 0, 6, 0, 0, 1, 0, 0, 0,
        10, 2, 0, 0, 1, 1, 5, 6, 0, 0, 0, 0, 0, 0,
        3, 0, 0, 0, 0, 0, 0, 11, 1, 0, 0, 0, 0, 0,
        4, 1, 1, 0, 0, 0, 0, 3, 0, 1, 0, 0, 0, 0,
        0, 0, 1, 1, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0,
        5, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0,
        7, 0, 1, 0, 0, 0, 0, 11, 1, 1, 0, 0, 0, 0,
        7, 0, 0, 0, 0, 0, 0, 10, 1, 0, 0, 0, 0, 0,
        12, 2, 0, 0, 1, 3, 7, 6, 1, 0, 1, 0, 0, 0,
        7, 1, 0, 1, 0, 0, 0, 4, 0, 0, 1, 0, 0, 0,
        3, 0, 0, 1, 0, 0, 0, 3, 0, 1, 1, 0, 0, 0,
        4, 0, 1, 0, 0, 0, 0, 6, 1, 0, 0, 0, 0, 0,
        11, 0, 0, 0, 0, 0, 0, 8, 0, 0, 1, 0, 0, 0,
        5, 0, 0, 1, 0, 0, 0, 14, 2, 0, 0, 1, 0, 9,
        5, 0, 0, 0, 0, 0, 0, 12, 1, 0, 0, 0, 0, 0,
        10, 1, 1, 0, 0, 0, 0, 4, 0, 1, 1, 0, 0, 0,
        12, 1, 1, 0, 0, 0, 0, 7, 1, 0, 0, 0, 0, 0,
        11, 0, 1, 0, 0, 0, 0, 10, 0, 0, 0, 0, 0, 0,
        13, 2, 0, 0, 1, 4, 8, 10, 0, 0, 1, 0, 0, 0,
        11, 0, 0, 1, 0, 0, 0, 9, 0, 1, 0, 0, 0, 0,
        8, 0, 1, 0, 0, 0, 0, 6, 2, 0, 0, 1, 11, 15,
        8, 0, 0, 0, 0, 0, 0, 9, 0, 0, 1, 0, 0, 0,
        14, 1, 0, 0, 0, 0, 0, 5, 1, 0, 1, 0, 0, 0,
        16, 0, 1, 1, 0, 0, 0, 8, 1, 0, 1, 0, 0, 0,
        5, 1, 0, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0,
        7, 2, 0, 0, 1, 12, 16, 12, 0, 1, 0, 0, 0, 0,
        10, 0, 1, 0, 0, 0, 0, 9, 0, 0, 0, 0, 0, 0,
        13, 1, 0, 0, 0, 0, 0, 16, 0, 0, 1, 0, 0, 0,
        15, 0, 1, 1, 0, 0, 0, 15, 0, 1, 0, 0, 0, 0,
        16, 0, 1, 0, 0, 0, 0, 14, 1, 1, 0, 0, 0, 0,
        13, 1, 1, 0, 0, 0, 0, 5, 2, 0, 0, 1, 10, 19,
        8, 1, 0, 0, 0, 0, 0, 14, 0, 0, 0, 0, 0, 0,
        9, 1, 0, 1, 0, 0, 0, 14, 0, 0, 1, 0, 0, 0,
        17, 0, 0, 1, 0, 0, 0, 12, 0, 0, 1, 0, 0, 0,
        16, 0, 0, 0, 0, 0, 0, 17, 0, 1, 1, 0, 0, 0,
        15, 0, 0, 1, 0, 0, 0, 16, 1, 0, 1, 0, 0, 0,
        9, 1, 0, 0, 0, 0, 0, 15, 0, 0, 0, 0, 0, 0,
        13, 0, 0, 0, 0, 0, 0, 8, 2, 0, 0, 1, 13, 17,
        13, 0, 1, 0, 0, 0, 0, 17, 1, 0, 1, 0, 0, 0,
        19, 0, 1, 0, 0, 0, 0, 14, 0, 1, 0, 0, 0, 0,
        19, 0, 1, 1, 0, 0, 0, 17, 0, 1, 0, 0, 0, 0,
        13, 0, 0, 1, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0,
        16, 1, 0, 0, 0, 0, 0, 9, 2, 0, 0, 1, 14, 18,
        15, 1, 0, 1, 0, 0, 0, 15, 1, 0, 0, 0, 0, 0,
        18, 0, 1, 1, 0, 0, 0, 18, 0, 0, 1, 0, 0, 0,
        19, 0, 0, 1, 0, 0, 0, 17, 1, 0, 0, 0, 0, 0,
        19, 0, 0, 0, 0, 0, 0, 18, 0, 1, 0, 0, 0, 0,
        18, 1, 0, 1, 0, 0, 0, 19, 2, 0, 0, 1, -1, -1,
        19, 1, 0, 0, 0, 0, 0, 18, 0, 0, 0, 0, 0, 0,
        19, 1, 0, 1, 0, 0, 0, 18, 1, 0, 0, 0, 0, 0,
    )

    private val FACE_CENTER_POINT = doubleArrayOf(
        0.2199307791404606, 0.6583691780274996, 0.7198475378926182,
        -0.2139234834501421, 0.1478171829550703, 0.9656017935214205,
        0.1092625278784797, -0.481195157287321, 0.8697775121287253,
        0.7428567301586791, -0.3593941678278028, 0.5648005936517033,
        0.8112534709140969, 0.3448953237639384, 0.472138773641393,
        -0.1055498149613921, 0.9794457296411413, 0.1718874610009365,
        -0.8075407579970092, 0.1533552485898818, 0.5695261994882688,
        -0.2846148069787907, -0.8644080972654206, 0.4144792552473539,
        0.7405621473854482, -0.6673299564565524, -0.0789837646326737,
        0.8512303986474293, 0.4722343788582681, -0.2289137388687808,
        -0.7405621473854481, 0.6673299564565524, 0.0789837646326737,
        -0.8512303986474292, -0.4722343788582682, 0.2289137388687808,
        0.1055498149613919, -0.9794457296411413, -0.1718874610009365,
        0.8075407579970092, -0.1533552485898819, -0.5695261994882688,
        0.2846148069787908, 0.8644080972654204, -0.4144792552473539,
        -0.7428567301586791, 0.3593941678278027, -0.5648005936517033,
        -0.811253470914097, -0.3448953237639382, -0.472138773641393,
        -0.2199307791404607, -0.6583691780274996, -0.7198475378926182,
        0.213923483450142, -0.1478171829550704, -0.9656017935214205,
        -0.1092625278784796, 0.481195157287321, -0.8697775121287253,
    )

    private val FACE_AXES_AZ_CII = doubleArrayOf(
        5.6199582685239395, 3.5255631661307447, 1.4311680637375488,
        5.7603390817141875, 3.665943979320992, 1.571548876927796,
        0.78021365439343, 4.969003859179821, 2.8746087567866256,
        0.4304693639799999, 4.619259568766391, 2.5248644663731956,
        6.130269123335111, 4.0358740209419155, 1.9414789185487202,
        2.692877706530643, 0.5984826041374471, 4.787272808923838,
        2.982963003477244, 0.8885679010840484, 5.07735810587044,
        3.532912002790141, 1.4385169003969456, 5.627307105183337,
        3.494305004259568, 1.3999099018663728, 5.588700106652764,
        3.0032141694995382, 0.908819067106343, 5.0976092718927335,
        5.930472956509812, 3.836077854116616, 1.7416827517234204,
        0.13837848409025486, 4.327168688876646, 2.23277358648345,
        0.4487149470591504, 4.6375051518455415, 2.543110049452346,
        0.15862965011254937, 4.3474198548989405, 2.2530247525057447,
        5.891865957979238, 3.797470855586043, 1.7030757531928475,
        2.711123289609793, 0.6167281872165977, 4.8055183920029885,
        3.294508837434268, 1.2001137350410729, 5.388903939827464,
        3.80481969224544, 1.7104245898522445, 5.8992147946386355,
        3.6644388790551923, 1.570043776661997, 5.758833981448388,
        2.361378999196363, 0.2669838968031676, 4.455774101589559,
    )
}
