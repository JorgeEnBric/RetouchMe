package com.example.retake_lite.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class FaceRepository(context: Context) {

    private val dao = FaceDatabase.getInstance(context).faceDao()

    fun getAllProfilesWithImages(): Flow<List<FaceProfileWithImages>> =
        dao.getAllProfilesWithImages()

    fun getAllProfiles(): Flow<List<FaceProfile>> = dao.getAllProfiles()

    suspend fun createProfile(name: String): Long =
        dao.insertProfile(FaceProfile(name = name))

    suspend fun renameProfile(profile: FaceProfile, newName: String) {
        dao.updateProfile(profile.copy(name = newName))
    }

    suspend fun deleteProfile(profile: FaceProfile) {
        dao.deleteProfile(profile)
    }

    suspend fun addFaceImage(image: FaceImageEntity): Long =
        dao.insertFaceImage(image)

    suspend fun deleteFaceImage(image: FaceImageEntity) {
        dao.deleteFaceImage(image)
    }

    suspend fun getImagesForProfile(profileId: Long): List<FaceImageEntity> =
        dao.getImagesForProfile(profileId)
}
