package local.phantom.companion.core.data.poi

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PoiDao {
    @Query("SELECT * FROM poi_cache WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<PoiEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPois(pois: List<PoiEntity>)

    @Query("DELETE FROM poi_cache WHERE timestamp < :expiryTime")
    suspend fun clearExpired(expiryTime: Long)
}
