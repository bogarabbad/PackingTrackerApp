package com.packingtracker.app.api

class ApiException(
    val code: String,
    val httpStatus: Int,
    message: String = code,
) : Exception(message)
