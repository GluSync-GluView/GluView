package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class PagedResultDto<T>(
    @SerializedName("page")         val page: Int,
    @SerializedName("pageSize")     val pageSize: Int,
    @SerializedName("totalRecords") val totalRecords: Int,
    @SerializedName("totalPages")   val totalPages: Int,
    @SerializedName("data")         val data: List<T>
)
