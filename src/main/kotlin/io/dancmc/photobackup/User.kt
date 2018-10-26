package io.dancmc.photobackup

data class User(
        var userID:String,
        val username:String,
        val passwordHash:String,
        val email:String,
        val emailVerified:Boolean,
        val firstName:String,
        val lastName:String

) {



}