package com.georgeb.retouchme.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceDao {

    @Transaction
    @Query("SELECT * FROM face_profiles ORDER BY createdAt ASC")
    fun getAllProfilesWithImages(): Flow<List<FaceProfileWithImages>>

    @Query("SELECT * FROM face_profiles ORDER BY createdAt ASC")
    fun getAllProfiles(): Flow<List<FaceProfile>>

    @Insert
    suspend fun insertProfile(profile: FaceProfile): Long

    @Update
    suspend fun updateProfile(profile: FaceProfile)

    @Delete
    suspend fun deleteProfile(profile: FaceProfile)

    @Insert
    suspend fun insertFaceImage(image: FaceImageEntity): Long

    @Delete
    suspend fun deleteFaceImage(image: FaceImageEntity)

    @Query("SELECT * FROM face_images WHERE profileId = :profileId")
    suspend fun getImagesForProfile(profileId: Long): List<FaceImageEntity>
}
