package com.vltv.play

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    var name: String,
    var imageUrl: String? = null, // Aqui salvaremos o link do TMDB
    val isKids: Boolean = false
)
