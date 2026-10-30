package local.phantom.companion.feature.stream.ui.map

import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.poi.PoiRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSearchPackAndRankTest {

    @Test
    fun `missingPackFor returns country name and sizeMb without posting toast`() = runBlocking {
        val pack = missingPackFor(50.08 to 14.42, installedCountries = emptyList())
        assertNotNull(pack)
        assertEquals("CZ", pack?.code)
        assertTrue(pack!!.nameEn.isNotBlank())
        assertTrue(pack.nameUa.isNotBlank())
        assertNull(pack.sizeMb)

        val sized = missingPackFor(50.08 to 14.42, installedCountries = emptyList(), sizeMb = 184)
        assertEquals(184, sized?.sizeMb)

        val notices = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val search = MapPoiSearch(
            scope = scope,
            poiRepository = emptyPoiRepository(),
            vaultStore = null,
            savedPlaceStore = null,
            postNotice = { notices += it },
            activeCorridor = { null },
        )

        search.offlineSearch("Pho Corner", 50.08, 14.42)

        assertEquals(SearchGap.NO_PACK, search.searchGap.value)
        assertTrue("expected no toast from offlineSearch, got $notices", notices.isEmpty())
    }

    @Test
    fun `anchorSubtitle formats travel minutes within 30 km and kilometres beyond`() {
        val origin = 50.0800 to 14.4200
        val near = anchorSubtitle(origin, 50.1205, 14.4200)
        assertEquals("18 min", near)

        val far = anchorSubtitle(origin, 52.0045, 14.4200)
        assertEquals("214 km", far)
    }

    @Test
    fun `wartime categories are gated by position`() {
        assertTrue(MapDiscovery.shortlistFor(false).none { it.ukraineWartime })
        assertTrue(MapDiscovery.shortlistFor(true).any { it.ukraineWartime })
        assertTrue(MapDiscovery.byGroupFor(false).flatMap { it.second }.none { it.ukraineWartime })
    }

    private fun emptyPoiRepository(): PoiRepository =
        instantiate(PoiRepository::class.java, 0) as PoiRepository

    private fun instantiate(cls: Class<*>, depth: Int): Any? {
        if (cls.isInterface) return proxyInterface(cls)
        val constructors = cls.declaredConstructors.sortedBy { it.parameterCount }
        val defaultMarker = constructors.firstOrNull { ctor ->
            ctor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        if (defaultMarker != null) {
            defaultMarker.isAccessible = true
            val params = defaultMarker.parameterTypes
            val args = Array<Any?>(params.size) { i ->
                val p = params[i]
                when {
                    p.name == "kotlin.jvm.internal.DefaultConstructorMarker" -> null
                    i == params.size - 2 && p.name == "int" -> -1
                    else -> defaultValueFor(p, depth + 1)
                }
            }
            val built = runCatching { defaultMarker.newInstance(*args) }.getOrNull()
            if (built != null) return built
        }
        for (ctor in constructors) {
            ctor.isAccessible = true
            val args = Array<Any?>(ctor.parameterCount) { i ->
                defaultValueFor(ctor.parameterTypes[i], depth + 1)
            }
            val built = runCatching { ctor.newInstance(*args) }.getOrNull()
            if (built != null) return built
        }
        return unsafeAllocate(cls, depth + 1)
    }

    private fun unsafeAllocate(cls: Class<*>, depth: Int): Any? = runCatching {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val instance = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, cls)
        if (depth <= 3 && instance != null) {
            var current: Class<*>? = cls
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    runCatching {
                        field.isAccessible = true
                        if (field.get(instance) == null) {
                            field.set(instance, defaultValueFor(field.type, depth + 1))
                        }
                    }
                }
                current = current.superclass
            }
        }
        instance
    }.getOrNull()

    private fun defaultValueFor(type: Class<*>, depth: Int): Any? = when {
        type.name == "boolean" || type.name == "java.lang.Boolean" -> false
        type.name == "int" || type.name == "java.lang.Integer" -> 0
        type.name == "long" || type.name == "java.lang.Long" -> 0L
        type.name == "double" || type.name == "java.lang.Double" -> 0.0
        type.name == "float" || type.name == "java.lang.Float" -> 0f
        type.name == "short" || type.name == "java.lang.Short" -> 0.toShort()
        type.name == "byte" || type.name == "java.lang.Byte" -> 0.toByte()
        type.name == "char" || type.name == "java.lang.Character" -> '\u0000'
        type.name == "java.lang.String" || type.name == "java.lang.CharSequence" -> ""
        type.name == "kotlin.Unit" || type.name == "void" -> Unit
        type.name == "java.io.File" -> File(".")
        type.isEnum -> type.enumConstants?.firstOrNull()
        StateFlow::class.java.isAssignableFrom(type) -> MutableStateFlow(emptyList<Any?>())
        Flow::class.java.isAssignableFrom(type) -> flowOf(emptyList<Any?>())
        List::class.java.isAssignableFrom(type) || Collection::class.java.isAssignableFrom(type) -> emptyList<Any?>()
        Set::class.java.isAssignableFrom(type) -> emptySet<Any?>()
        Map::class.java.isAssignableFrom(type) -> emptyMap<Any?, Any?>()
        type.isInterface -> proxyInterface(type)
        depth > 3 -> null
        else -> instantiate(type, depth)
    }

    private fun proxyInterface(iface: Class<*>): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "Fake${iface.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "searchOfflineVerdict" -> PoiRepository.OfflineSearch.NowhereToLook
                else -> {
                    val target = if (method.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation") {
                        continuationReturnClass(method.genericParameterTypes.lastOrNull())
                    } else {
                        method.returnType
                    }
                    val targetName = target?.name
                    when {
                        targetName == "boolean" || targetName == "java.lang.Boolean" -> false
                        targetName == "int" || targetName == "java.lang.Integer" -> 0
                        targetName == "long" || targetName == "java.lang.Long" -> 0L
                        targetName == "double" || targetName == "java.lang.Double" -> 0.0
                        targetName == "float" || targetName == "java.lang.Float" -> 0f
                        targetName == "java.lang.String" -> ""
                        targetName == "kotlin.Unit" || targetName == "void" -> Unit
                        target != null && StateFlow::class.java.isAssignableFrom(target) ->
                            MutableStateFlow(emptyList<Any?>())
                        target != null && Flow::class.java.isAssignableFrom(target) ->
                            flowOf(emptyList<Any?>())
                        target != null && (List::class.java.isAssignableFrom(target) ||
                            Collection::class.java.isAssignableFrom(target)) -> emptyList<Any?>()
                        target != null && Set::class.java.isAssignableFrom(target) -> emptySet<Any?>()
                        target != null && Map::class.java.isAssignableFrom(target) -> emptyMap<Any?, Any?>()
                        method.name.startsWith("count") || method.name.startsWith("size") -> 0
                        method.name.startsWith("has") || method.name.startsWith("is") -> false
                        else -> emptyList<Any?>()
                    }
                }
            }
        }

    private fun continuationReturnClass(contType: Type?): Class<*>? {
        val param = contType as? ParameterizedType ?: return null
        val arg = param.actualTypeArguments.firstOrNull() ?: return null
        val unwrapped = when (arg) {
            is WildcardType -> arg.lowerBounds.firstOrNull() ?: arg.upperBounds.firstOrNull()
            else -> arg
        }
        return when (unwrapped) {
            is Class<*> -> unwrapped
            is ParameterizedType -> unwrapped.rawType as? Class<*>
            else -> null
        }
    }
}
