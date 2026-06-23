package com.example.retake_lite.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "face_profiles")
data class FaceProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
