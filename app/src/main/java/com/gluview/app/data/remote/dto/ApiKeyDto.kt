package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// F5 · API Keys — DTOs do contrato GluSync (/apikeys). JSON em camelCase (ASP.NET default).

data class CreateApiKeyRequestDto(
    @SerializedName("name")   val name: String,
    @SerializedName("scopes") val scopes: List<String>? = null
)

/** Resposta da criação — a chave COMPLETA (`key`) só vem aqui, uma única vez. */
data class CreatedApiKeyDto(
    @SerializedName("id")        val id: String,
    @SerializedName("name")      val name: String,
    @SerializedName("key")       val key: String,
    @SerializedName("prefix")    val prefix: String,
    @SerializedName("scopes")    val scopes: List<String> = emptyList(),
    @SerializedName("createdAt") val createdAt: String? = null
)

/** Listagem — nunca expõe a chave nem o hash. */
data class ApiKeyInfoDto(
    @SerializedName("id")         val id: String,
    @SerializedName("name")       val name: String,
    @SerializedName("prefix")     val prefix: String,
    @SerializedName("scopes")     val scopes: List<String> = emptyList(),
    @SerializedName("revoked")    val revoked: Boolean = false,
    @SerializedName("createdAt")  val createdAt: String? = null,
    @SerializedName("lastUsedAt") val lastUsedAt: String? = null
)
