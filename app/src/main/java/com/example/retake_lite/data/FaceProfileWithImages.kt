package com.example.retake_lite.data

import androidx.room.Embedded
import androidx.room.Relation

data class FaceProfileWithImages(
    @Embedded val profile: FaceProfile,
    @Relation(parentColumn = "id", entityColumn = "profileId")
    val images: List<FaceImageEntity>
)
