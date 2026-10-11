package com.invocta.data.remote.api

open class ApiException(message: String) : Exception(message)

class AuthRequiredException : ApiException("Authentication required - please sign in to continue")
