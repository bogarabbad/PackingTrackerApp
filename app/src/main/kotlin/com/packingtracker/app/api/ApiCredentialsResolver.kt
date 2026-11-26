package com.packingtracker.app.api

import com.packingtracker.app.auth.UserSession
import com.packingtracker.app.config.AppConfig

object ApiCredentialsResolver {
    fun resolve(config: AppConfig, session: UserSession): ApiCredentials? {
        if (config.serverUrl.isBlank() || config.token.isBlank() || !config.enrolled) {
            return null
        }
        return if (config.requiereAuth) {
            if (!session.isValid() || session.accessToken.isBlank()) {
                null
            } else {
                ApiCredentials(
                    authorizationBearer = session.accessToken,
                    deviceToken = config.token,
                )
            }
        } else {
            ApiCredentials(authorizationBearer = config.token)
        }
    }
}
