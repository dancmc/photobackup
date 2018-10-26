package io.dancmc.photobackup

class DBResult(val result :OUTCOME, val payload:Any?=null, val errorMessage:String="") {


    enum class OUTCOME{
        SUCCESSFUL,FAILED
    }

}