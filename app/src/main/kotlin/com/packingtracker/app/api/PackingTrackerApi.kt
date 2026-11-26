package com.packingtracker.app.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PackingTrackerApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val root = baseUrl.trim().trimEnd('/')

    fun enroll(request: EnrollmentRequest): EnrollmentResult {
        val bodyJson = JSONObject()
            .put("TOKEN", request.token)
            .put("IDENTIFICADOR", request.identificador)
            .toString()

        val httpRequest = Request.Builder()
            .url("$root/enrollment")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()

        return executeObject(httpRequest) { data, code ->
            EnrollmentResult(
                dispositivo = parseDispositivo(data.getJSONObject("DISPOSITIVO")),
                token = parseToken(data.getJSONObject("TOKEN")),
                creado = data.getBoolean("CREADO"),
                httpStatus = code,
            )
        }
    }

    fun getDeviceSession(deviceToken: String): DeviceSession {
        val httpRequest = Request.Builder()
            .url("$root/auth/session")
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $deviceToken")
            .build()

        return executeObject(httpRequest) { data, _ ->
            val dispositivo = data.getJSONObject("DISPOSITIVO")
            val token = data.getJSONObject("TOKEN")
            DeviceSession(
                requiereAuth = data.getBoolean("REQUIERE_AUTH"),
                dispositivoId = dispositivo.getInt("ID"),
                dispositivoIdentificador = dispositivo.getString("IDENTIFICADOR"),
                dispositivoNombre = dispositivo.nullableString("NOMBRE"),
                tokenId = token.getInt("ID"),
                tokenNombre = token.getString("NOMBRE"),
            )
        }
    }

    fun login(request: LoginRequest): LoginResult {
        val bodyJson = JSONObject()
            .put("CODIGO", request.codigo)
            .put("CONTRASENA", request.contrasena)
            .toString()

        val httpRequest = Request.Builder()
            .url("$root/auth/login")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()

        return executeObject(httpRequest) { data, _ ->
            val usuario = data.getJSONObject("usuario")
            LoginResult(
                accessToken = data.getString("access_token"),
                tokenType = data.getString("token_type"),
                expiresInSeconds = data.getInt("expires_in"),
                usuarioId = usuario.getInt("ID"),
                usuarioCodigo = usuario.getString("CODIGO"),
                usuarioNombre = usuario.getString("NOMBRE"),
            )
        }
    }

    fun listLotesTipos(
        credentials: ApiCredentials,
        activo: Boolean = true,
        items: Int = 100,
    ): List<LoteTipo> {
        val url = "$root/lotes-tipos".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("items", items.toString())
            .addQueryParameter("activo", activo.toString())
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .applyCredentials(credentials)
            .build()

        return executeArray(httpRequest) { array ->
            array.mapObjects { parseLoteTipo(it) }
        }
    }

    fun listUnidadesTransporte(
        credentials: ApiCredentials,
        activo: Boolean = true,
        items: Int = 100,
    ): List<UnidadTransporte> {
        val url = "$root/unidades-transporte".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("items", items.toString())
            .addQueryParameter("activo", activo.toString())
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .applyCredentials(credentials)
            .build()

        return executeArray(httpRequest) { array ->
            array.mapObjects { parseUnidadTransporte(it) }
        }
    }

    fun createLote(credentials: ApiCredentials, request: CreateLoteRequest): Lote {
        val bodyJson = JSONObject()
            .put("ID_TIPO", request.idTipo)
            .put("PADRE_ID", request.padreId ?: JSONObject.NULL)
            .put("ID_UNIDAD", request.idUnidad ?: JSONObject.NULL)
            .put("USUARIO_ID", request.usuarioId ?: JSONObject.NULL)
            .put("ID_ORIGEN_USUARIO", request.idOrigenUsuario ?: JSONObject.NULL)
            .toString()

        val httpRequest = Request.Builder()
            .url("$root/lotes")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .applyCredentials(credentials)
            .build()

        return executeObject(httpRequest) { data, _ -> parseLote(data) }
    }

    fun deleteLote(credentials: ApiCredentials, id: Int) {
        val httpRequest = Request.Builder()
            .url("$root/lotes/$id")
            .delete()
            .header("Accept", "application/json")
            .applyCredentials(credentials)
            .build()

        executeEmpty(httpRequest)
    }

    fun listLotes(
        credentials: ApiCredentials,
        page: Int = 1,
        items: Int = 20,
        esFinal: Boolean? = null,
    ): PagedResult<Lote> {
        val urlBuilder = "$root/lotes".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("items", items.toString())
        if (esFinal != null) {
            urlBuilder.addQueryParameter("es_final", esFinal.toString())
        }

        val httpRequest = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("Accept", "application/json")
            .applyCredentials(credentials)
            .build()

        return executePaged(httpRequest) { array, meta ->
            PagedResult(
                items = array.mapObjects { parseLote(it) },
                meta = meta,
            )
        }
    }

    private fun Request.Builder.applyCredentials(credentials: ApiCredentials): Request.Builder {
        header("Authorization", "Bearer ${credentials.authorizationBearer}")
        val deviceToken = credentials.deviceToken
        if (!deviceToken.isNullOrBlank()) {
            header(DEVICE_TOKEN_HEADER, deviceToken)
        }
        return this
    }

    private fun <T> executeObject(
        httpRequest: Request,
        mapper: (JSONObject, Int) -> T,
    ): T {
        return execute(httpRequest) { data, _, code ->
            val obj = data as? JSONObject
                ?: throw ApiException(code = "invalid_response", httpStatus = code)
            mapper(obj, code)
        }
    }

    private fun <T> executeArray(
        httpRequest: Request,
        mapper: (JSONArray) -> T,
    ): T {
        return execute(httpRequest) { data, _, code ->
            val array = data as? JSONArray
                ?: throw ApiException(code = "invalid_response", httpStatus = code)
            mapper(array)
        }
    }

    private fun <T> executePaged(
        httpRequest: Request,
        mapper: (JSONArray, PageMeta) -> T,
    ): T {
        return execute(httpRequest) { data, metadata, code ->
            val array = data as? JSONArray
                ?: throw ApiException(code = "invalid_response", httpStatus = code)
            val metaJson = metadata ?: JSONObject()
            val meta = PageMeta(
                page = metaJson.optInt("page", 1),
                items = metaJson.optInt("items", array.length()),
                total = metaJson.optInt("total", array.length()),
                pages = metaJson.optInt("pages", if (array.length() == 0) 0 else 1),
            )
            mapper(array, meta)
        }
    }

    private fun executeEmpty(httpRequest: Request) {
        client.newCall(httpRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val envelope = parseEnvelope(raw)
            val errorCode = envelope.optJSONObject("error")?.optString("error")?.takeIf { it.isNotBlank() }

            if (!response.isSuccessful || errorCode != null) {
                throw ApiException(
                    code = errorCode ?: "http_${response.code}",
                    httpStatus = response.code,
                )
            }
        }
    }

    private fun <T> execute(
        httpRequest: Request,
        mapper: (Any, JSONObject?, Int) -> T,
    ): T {
        client.newCall(httpRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val envelope = parseEnvelope(raw)
            val errorCode = envelope.optJSONObject("error")?.optString("error")?.takeIf { it.isNotBlank() }

            if (!response.isSuccessful || errorCode != null) {
                throw ApiException(
                    code = errorCode ?: "http_${response.code}",
                    httpStatus = response.code,
                )
            }

            val data = envelope.opt("data")
                ?: throw ApiException(code = "invalid_response", httpStatus = response.code)

            return mapper(data, envelope.optJSONObject("_metadata"), response.code)
        }
    }

    private fun parseEnvelope(raw: String): JSONObject {
        if (raw.isBlank()) {
            return JSONObject()
        }
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun parseDispositivo(json: JSONObject): Dispositivo = Dispositivo(
        id = json.getInt("ID"),
        idTipo = json.getInt("ID_TIPO"),
        identificador = json.getString("IDENTIFICADOR"),
        nombre = json.nullableString("NOMBRE"),
        activo = json.getBoolean("ACTIVO"),
        creadoEn = json.getString("CREADO_EN"),
        actualizadoEn = json.getString("ACTUALIZADO_EN"),
    )

    private fun parseToken(json: JSONObject): AutorizacionToken = AutorizacionToken(
        id = json.getInt("ID"),
        idDispositivo = json.nullableInt("ID_DISPOSITIVO"),
        token = json.getString("TOKEN"),
        nombre = json.getString("NOMBRE"),
        activo = json.getBoolean("ACTIVO"),
        requiereAuth = if (json.has("REQUIERE_AUTH") && !json.isNull("REQUIERE_AUTH")) {
            json.getBoolean("REQUIERE_AUTH")
        } else {
            true
        },
        expiraEn = json.nullableString("EXPIRA_EN"),
        ultimoUsoEn = json.nullableString("ULTIMO_USO_EN"),
        creadoEn = json.getString("CREADO_EN"),
        actualizadoEn = json.getString("ACTUALIZADO_EN"),
    )

    private fun parseLoteTipo(json: JSONObject): LoteTipo = LoteTipo(
        id = json.getInt("ID"),
        codigo = json.getString("CODIGO"),
        nombre = json.getString("NOMBRE"),
        activo = json.getBoolean("ACTIVO"),
        esFinal = json.getBoolean("ES_FINAL"),
    )

    private fun parseUnidadTransporte(json: JSONObject): UnidadTransporte = UnidadTransporte(
        id = json.getInt("ID"),
        codigo = json.getString("CODIGO"),
        placas = json.nullableString("PLACAS"),
        numeroEconomico = json.nullableString("NUMERO_ECONOMICO"),
        activo = json.getBoolean("ACTIVO"),
    )

    private fun parseLote(json: JSONObject): Lote = Lote(
        id = json.getInt("ID"),
        idTipo = json.getInt("ID_TIPO"),
        padreId = json.nullableInt("PADRE_ID"),
        idUnidad = json.nullableInt("ID_UNIDAD"),
        usuarioId = json.nullableInt("USUARIO_ID"),
        idOrigenUsuario = json.nullableInt("ID_ORIGEN_USUARIO"),
        creadoEn = json.getString("CREADO_EN"),
        actualizadoEn = json.getString("ACTUALIZADO_EN"),
    )

    private fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> {
        val items = ArrayList<T>(length())
        for (index in 0 until length()) {
            items.add(mapper(getJSONObject(index)))
        }
        return items
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = getString(key)
        return value.ifBlank { null }
    }

    private fun JSONObject.nullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return getInt(key)
    }

    companion object {
        const val DEVICE_TOKEN_HEADER = "X-Device-Token"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
