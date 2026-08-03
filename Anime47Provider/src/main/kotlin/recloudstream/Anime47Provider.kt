package recloudstream

import android.content.SharedPreferences
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private val mapper: ObjectMapper = jacksonObjectMapper().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

private fun toJson(value: Any?): String {
    return try {
        mapper.writeValueAsString(value)!!
    } catch (e: Exception) {
        value.toString()
    }
}

class Anime47Provider : MainAPI() {

    override var mainUrl = "https://anime47.best"
    private val apiBaseUrl = "https://anime47.love/api"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon)

    private val interceptor = CloudflareKiller()

    // Chỉ dùng làm cache trong RAM cho lượt gọi hiện tại; nguồn "thật" là SharedPreferences
    // bên dưới, để token sống sót qua việc Provider bị CloudStream tạo lại instance mới
    // (xảy ra khi app reload plugin / khởi động lại), tránh phải login lại liên tục.
    private var cachedToken: String? = null

    // Khóa toàn bộ quá trình login để nhiều coroutine (vd: xem nhiều tập / info+episodes+recs
    // chạy song song) không cùng lúc phát hiện token hết hạn rồi gọi login() song song, dẫn
    // tới việc token mới bị ghi đè bởi 1 lượt login khác chạy chậm hơn.
    private val loginMutex = Mutex()

    // Token coi như còn hạn dùng trong khoảng thời gian này kể từ lúc login thành công.
    // Giá trị an toàn, ngắn hơn thời hạn thật của server, để chủ động refresh trước khi
    // server từ chối request (tránh lỗi giữa chừng khi đang xem/tải tập).
    private val tokenTtlMs = 20 * 60 * 1000L // 20 phút

    private val prefs: SharedPreferences?
        get() {
            val activity = CommonActivity.activity ?: return null
            return activity.getSharedPreferences("anime47_prefs", 0)
        }

    private fun loadPersistedToken(): String? {
        val savedToken = prefs?.getString("anime47_token", null)
        val savedAt = prefs?.getLong("anime47_token_saved_at", 0L) ?: 0L
        if (savedToken.isNullOrBlank()) return null
        val age = System.currentTimeMillis() - savedAt
        if (age < 0 || age > tokenTtlMs) return null
        return savedToken
    }

    private fun persistToken(token: String?) {
        val editor = prefs?.edit() ?: return
        if (token.isNullOrBlank()) {
            editor.remove("anime47_token")
            editor.remove("anime47_token_saved_at")
        } else {
            editor.putString("anime47_token", token)
            editor.putLong("anime47_token_saved_at", System.currentTimeMillis())
        }
        editor.apply()
    }

    override val mainPage: List<MainPageData> = mainPageOf(
        "/anime/filter?lang=vi&sort=latest" to "Anime Mới Cập Nhật",
        "/anime/filter?lang=vi&sort=rating" to "Top Đánh Giá",
        "/anime/filter?lang=vi&type=tv" to "Anime TV",
        "/anime/filter?lang=vi&type=movie" to "Anime Movie"
    )

    private val subtitleLanguageMap: Map<String, List<String>> = mapOf(
        "Vietnamese" to listOf("tiếng việt", "vietnamese", "vietsub", "viet", "vi"),
        "English" to listOf("tiếng anh", "english", "engsub", "eng", "en")
    )

    // ===================== Helper methods =====================

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("via.placeholder.com", ignoreCase = true)) return null
        if (url.startsWith("http", ignoreCase = true)) return url
        if (url.startsWith("//")) return "https:$url"

        val path = if (url.startsWith("/")) url else "/$url"
        return if (mainUrl.startsWith("http", ignoreCase = true)) {
            "$mainUrl$path"
        } else {
            "https:$mainUrl$path"
        }
    }

    private fun createSearchResponse(
        title: String,
        poster: String?,
        link: String,
        year: Int? = null,
        episodesStr: String? = null
    ): SearchResponse {
        val episodes: Int? = episodesStr?.let { str ->
            val digitsOnly = str.filter { it.isDigit() }
            digitsOnly.toIntOrNull()
        }

        return newAnimeSearchResponse(title, link, TvType.Anime) {
            this.posterUrl = fixUrl(poster)
            this.year = year
            if (episodes != null) {
                addDubStatus(DubStatus.Subbed, episodes)
            }
        }
    }

    private fun toTvType(detail: DetailPost): TvType {
        // Lưu ý: luôn trả về TvType.Anime (hoặc Cartoon) thay vì TvType.AnimeMovie / TvType.OVA.
        // Lý do: CloudStream hiển thị UI "single play" (không có danh sách tập) cho các
        // TvType thuộc nhóm Movie/OVA, nên nếu một "movie" trên Anime47 thực chất có nhiều
        // tập/phần (rất phổ biến với OVA, special, hoặc movie nhiều phần), toàn bộ các tập
        // từ tập 2 trở đi sẽ bị ẩn khỏi người dùng ("mất tập"). Dùng TvType.Anime cho mọi
        // trường hợp để app luôn hiển thị danh sách tập đầy đủ, kể cả khi chỉ có 1 tập.
        return when {
            detail.title != null && detail.title.contains("Hoạt Hình Trung Quốc", ignoreCase = true) -> TvType.Cartoon
            else -> TvType.Anime
        }
    }

    private fun mapSubtitleLabel(label: String): String {
        val trimmedLower = label.trim().lowercase(java.util.Locale.ROOT)
        if (trimmedLower.isBlank()) return "Subtitle"

        for ((standardName, keywords) in subtitleLanguageMap) {
            if (keywords.any { trimmedLower.contains(it) }) {
                return standardName
            }
        }

        val trimmed = label.trim()
        return if (trimmed.isNotEmpty()) {
            val firstChar = trimmed[0]
            val firstCharUpper = if (firstChar.isLowerCase()) {
                firstChar.titlecase(java.util.Locale.ROOT)
            } else {
                firstChar.toString()
            }
            firstCharUpper + trimmed.substring(1)
        } else {
            trimmed
        }
    }

    private fun findMpegTsOffset(data: ByteArray): Int {
        val packetSize = 188
        val minLen = packetSize * 3
        if (data.size < minLen) return -1

        for (i in 0 until (data.size - minLen)) {
            if (data[i] == 0x47.toByte() &&
                data[i + packetSize] == 0x47.toByte() &&
                data[i + packetSize * 2] == 0x47.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    // forceRelogin=true bỏ qua token đang cache (dùng khi token cũ đã bị server từ chối)
    // và luôn gọi lại /auth/login bằng tài khoản đã lưu trong Settings.
    private suspend fun ensureToken(forceRelogin: Boolean = false): String? {
        // Thử cache RAM trước (nhanh nhất, tránh chạm mutex khi không cần thiết).
        val existing = cachedToken
        if (!forceRelogin && !existing.isNullOrBlank()) {
            return existing
        }

        // Toàn bộ phần đọc-hoặc-login được khóa lại: nếu 2 coroutine (vd đang tải nhiều
        // tập song song) cùng thấy cache RAM rỗng, chỉ 1 coroutine thực sự gọi /auth/login;
        // coroutine còn lại sẽ đợi rồi dùng luôn kết quả vừa có, thay vì tự login lần nữa
        // và có nguy cơ ghi đè token mới bằng 1 response tới sau.
        return loginMutex.withLock {
            // Re-check sau khi có lock: có thể 1 coroutine khác đã login xong trong lúc đợi.
            val afterLockExisting = cachedToken
            if (!forceRelogin && !afterLockExisting.isNullOrBlank()) {
                return@withLock afterLockExisting
            }

            // Nếu không forceRelogin, thử token đã lưu trên đĩa (sống sót qua việc Provider
            // bị tạo lại instance mới) trước khi phải gọi API login lại từ đầu.
            if (!forceRelogin) {
                val persisted = loadPersistedToken()
                if (!persisted.isNullOrBlank()) {
                    cachedToken = persisted
                    return@withLock persisted
                }
            }

            val email = prefs?.getString("anime47_email", "") ?: ""
            val password = prefs?.getString("anime47_password", "") ?: ""

            if (email.isBlank() || password.isBlank()) {
                cachedToken = null
                persistToken(null)
                return@withLock null
            }

            try {
                val body = toJson(LoginRequest(email, password))
                    .toRequestBody("application/json".toMediaTypeOrNull())

                val response = app.post(
                    "$apiBaseUrl/auth/login",
                    headers = mapOf(
                        "origin" to mainUrl,
                        "referer" to "$mainUrl/"
                    ),
                    requestBody = body,
                    interceptor = interceptor,
                    timeout = 15000
                )

                val loginResponse: LoginResponse = mapper.readValue(
                    response.text,
                    object : TypeReference<LoginResponse>() {}
                )
                // Nếu login thất bại (sai tài khoản, server lỗi...) access_token sẽ null;
                // đảm bảo không giữ lại token cũ đã biết là hỏng.
                cachedToken = loginResponse.access_token
                persistToken(loginResponse.access_token)
                cachedToken
            } catch (e: Exception) {
                cachedToken = null
                persistToken(null)
                null
            }
        }
    }

    private suspend fun getAuthHeaders(forceRelogin: Boolean = false): Map<String, String> {
        val token = ensureToken(forceRelogin)
        return if (token != null) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
    }

    private fun looksLikeSessionExpired(text: String): Boolean {
        return text.contains("\"PRIVATE_MODE\"") ||
            text.contains("\"Unauthorized\"", ignoreCase = true) ||
            text.contains("\"UNAUTHENTICATED\"", ignoreCase = true)
    }

    private suspend inline fun <reified T> fetchApi(url: String): T {
        val headers = getAuthHeaders()
        val text = app.get(
            url,
            headers = headers,
            interceptor = interceptor,
            timeout = 15000
        ).text

        if (looksLikeSessionExpired(text)) {
            // Token đang cache đã bị server từ chối (hết hạn / thu hồi).
            // Xóa token cũ (cả RAM lẫn bản lưu trên đĩa), login lại bằng tài khoản đã lưu,
            // rồi thử lại request 1 lần trước khi báo lỗi cho người dùng — không cần khởi
            // động lại app.
            cachedToken = null
            persistToken(null)
            val retryHeaders = getAuthHeaders(forceRelogin = true)

            if (retryHeaders.isEmpty()) {
                throw ErrorLoadingException("Trang web yêu cầu đăng nhập. Vui lòng mở cài đặt tiện ích để cấu hình tài khoản.")
            }

            val retryText = app.get(
                url,
                headers = retryHeaders,
                interceptor = interceptor,
                timeout = 15000
            ).text

            if (looksLikeSessionExpired(retryText)) {
                throw ErrorLoadingException("Phiên đăng nhập đã hết hạn và đăng nhập lại không thành công. Vui lòng kiểm tra lại tài khoản trong cài đặt tiện ích.")
            }

            return mapper.readValue(retryText, object : TypeReference<T>() {})
        }

        return mapper.readValue(text, object : TypeReference<T>() {})
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$apiBaseUrl${request.data}&page=$page"

        val response: ApiFilterResponse? = try {
            fetchApi(url)
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            null
        }

        val posts = response?.data?.posts
            ?: throw ErrorLoadingException("Cấu trúc dữ liệu trang chủ đã thay đổi hoặc tài khoản chưa kích hoạt.")

        val items = posts.mapNotNull { post ->
            val link = fixUrl(post.link) ?: return@mapNotNull null
            createSearchResponse(
                post.title,
                post.poster,
                link,
                post.year?.toIntOrNull(),
                post.current_episode ?: post.episodes
            )
        }

        return newHomePageResponse(request.name, items, items.size >= 24)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiBaseUrl/search/full/?lang=vi&keyword=$encoded&page=1"

        val response: ApiSearchResponse? = try {
            fetchApi(url)
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            null
        }

        val results = response?.results ?: return emptyList()

        return results.mapNotNull { item ->
            val link = fixUrl(item.link) ?: return@mapNotNull null
            createSearchResponse(
                item.title,
                item.image,
                link,
                null,
                item.current_episode ?: item.episodes
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = Regex("(\\d+)(?:\\.html|/)?$")
            .find(url.trimEnd('/'))
            ?.groupValues
            ?.get(1)

        if (animeId.isNullOrBlank() || animeId.toIntOrNull() == null) {
            throw IllegalArgumentException("Invalid anime ID from URL")
        }

        try {
            val (infoResponse, episodeResponse, recsResponse) = coroutineScope {
                val infoTask = async {
                    fetchApi<ApiDetailResponse>("$apiBaseUrl/anime/info/$animeId?lang=vi")
                }
                val episodesTask = async {
                    fetchApi<ApiEpisodeResponse>("$apiBaseUrl/anime/$animeId/episodes?lang=vi")
                }
                val recsTask = async {
                    fetchApi<ApiRecommendationResponse>("$apiBaseUrl/anime/info/$animeId/recommendations?lang=vi")
                }
                Triple(infoTask.await(), episodesTask.await(), recsTask.await())
            }

            val detail = infoResponse?.data ?: throw IOException("Data is null")

            val title = detail.title ?: "Unknown Title"
            val posterUrl = fixUrl(detail.poster)
            val plot = detail.description
            val tags = detail.genres
                ?.mapNotNull { it.name }
                ?.filter { it.isNotBlank() }
            val year = detail.year?.toIntOrNull()
            val tvType = toTvType(detail)
            val score = detail.score?.toString()?.let { Score.from10(it) }

            val actors = detail.characters?.mapNotNull { character ->
                val name = character.name ?: return@mapNotNull null
                ActorData(
                    Actor(name, fixUrl(character.image_url)),
                    roleString = character.role
                )
            }

            val episodeItems = episodeResponse?.teams
                ?.flatMap { it.groups }
                ?.flatMap { it.episodes }
                ?.filter { it.number != null }

            val episodes = if (episodeItems != null) {
                episodeItems
                    .groupBy { it.number!! }
                    .map { (number, items) ->
                        val ids = items.map { it.id }.distinct()
                        val data = toJson(ids)
                        newEpisode(data) {
                            this.name = "Tập $number"
                            this.episode = number
                        }
                    }
                    .sortedBy { it.episode }
            } else {
                emptyList()
            }

            val recommendations = recsResponse?.data?.mapNotNull { item ->
                val link = fixUrl(item.link) ?: return@mapNotNull null
                createSearchResponse(
                    item.title ?: "",
                    item.poster,
                    link,
                    item.year?.toIntOrNull(),
                    item.current_episode ?: item.episodes
                )
            }

            return newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.score = score
                this.actors = actors
                this.recommendations = recommendations
                this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
            }
        } catch (e: Exception) {
            throw IOException("Lỗi tải thông tin phim: ${e.message}", e)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeIds: List<Int> = try {
            if (data.startsWith("[")) {
                mapper.readValue(data, object : TypeReference<List<Int>>() {})
            } else {
                listOf(data.toInt())
            }
        } catch (e: Exception) {
            return false
        }

        if (episodeIds.isEmpty()) return false

        val loaded = AtomicBoolean(false)
        val referer = "$mainUrl/"

        coroutineScope {
            episodeIds.map { id ->
                async {
                    try {
                        val watchResponse: ApiWatchResponse? =
                            fetchApi("$apiBaseUrl/anime/watch/episode/$id?lang=vi")

                        val streams = watchResponse?.streams ?: return@async

                        for (stream in streams) {
                            val url = stream.url
                            val serverName = stream.server_name

                            if (url.isNullOrBlank()) continue

                            // Server "HY" (Hydrax/Abyss.to) không trả về m3u8 thật, mà là một trang
                            // embed chứa metadata mã hóa AES-CTR (xem HydraxExtractor.kt). Phải đi
                            // qua HydraxExtractor + HydraxInterceptor thay vì coi url là m3u8 trực tiếp.
                            if (HydraxExtractor.isHydraxUrl(url)) {
                                try {
                                    val hydraxLinks = HydraxExtractor.getLinks(
                                        streamUrl = url,
                                        providerName = this@Anime47Provider.name,
                                        serverName = serverName,
                                        referer = referer
                                    )
                                    hydraxLinks.forEach { callback(it) }
                                    if (hydraxLinks.isNotEmpty()) loaded.set(true)
                                } catch (e: Exception) {
                                    // bỏ qua lỗi riêng của HY, không chặn các server khác
                                }

                                stream.subtitles?.forEach { subtitle ->
                                    if (!subtitle.file.isNullOrBlank()) {
                                        val label = mapSubtitleLabel(subtitle.label ?: "Vietnamese")
                                        subtitleCallback(SubtitleFile(label, subtitle.file))
                                    }
                                }
                                continue
                            }

                            // Chấp nhận mọi server có URL hợp lệ (FE, HY, hoặc bất kỳ server nào khác),
                            // thay vì chỉ giới hạn ở "FE"/jwplayer và loại trừ "HY" như logic gốc.
                            val headers = mutableMapOf(
                                "Referer" to referer,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                                "sec-ch-ua" to "\"Chromium\";v=\"120\", \"Not?A_Brand\";v=\"24\"",
                                "sec-ch-ua-mobile" to "?1",
                                "sec-ch-ua-platform" to "\"Android\""
                            )

                            if (url.contains("vlogphim.net")) {
                                headers["Origin"] = referer
                                try {
                                    val host = java.net.URL(url).host
                                    headers["authority"] = host
                                } catch (e: Exception) {
                                    headers["authority"] = "pl.vlogphim.net"
                                }
                            }

                            val link = newExtractorLink(
                                this@Anime47Provider.name,
                                serverName ?: this@Anime47Provider.name,
                                url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer
                                this.headers = headers
                                this.quality = Qualities.Unknown.value
                            }

                            callback(link)
                            loaded.set(true)

                            stream.subtitles?.forEach { subtitle ->
                                if (!subtitle.file.isNullOrBlank()) {
                                    val label = mapSubtitleLabel(subtitle.label ?: "Vietnamese")
                                    subtitleCallback(SubtitleFile(label, subtitle.file))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // bỏ qua lỗi từng episode riêng lẻ
                    }
                }
            }.awaitAll()
        }

        return loaded.get()
    }

    /**
     * LƯU Ý: Class ẩn danh gốc "Anime47Provider$getVideoInterceptor$1" (triển khai Interceptor)
     * KHÔNG có trong file .cs3 / bản decompile được cung cấp, nên phần dưới đây được suy luận
     * hợp lý từ tên hàm findMpegTsOffset() và regex domain, không phải dịch chính xác 100% từ bytecode gốc.
     * Vui lòng kiểm tra và điều chỉnh lại nếu bạn có bản gốc chính xác hơn.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        // Link Hydrax/Abyss trỏ về host relay nội bộ (xem HydraxExtractor.buildRelayUrl);
        // mọi request Range của player phải đi qua HydraxInterceptor để dịch sang giao thức
        // segment-token thật của Abyss. Không chạm vào logic CDN nonprofit.asia bên dưới.
        if (extractorLink.url.contains(HydraxExtractor.RELAY_HOST)) {
            return HydraxInterceptor
        }

        val cdnRegex = Regex("nonprofit\\.asia|cdn\\d+\\.nonprofit")
        // Ngưỡng an toàn: các segment .ts của HLS thường chỉ vài trăm KB tới vài MB.
        // Nếu response nhỏ hơn ngưỡng này, buffer toàn bộ để quét offset CHÍNH XÁC 100%
        // (giống hệt logic gốc dùng body.bytes()) — vì bug tải-về-bị-lỗi ở server FE
        // trước đây là do giới hạn cửa sổ quét quá nhỏ (65KB) khiến những segment có
        // phần rác ở đầu dài hơn dự kiến bị bỏ sót, không cắt được, làm hỏng dữ liệu khi
        // CloudStream ghép các segment .ts lại thành 1 file tải về.
        // Chỉ khi response VƯỢT ngưỡng này (khả năng cao là đang tải nguyên 1 file lớn,
        // không phải từng segment .ts nhỏ) mới chuyển sang chế độ quét cửa sổ giới hạn +
        // stream lười, để tránh OutOfMemoryError.
        val fullBufferThreshold = 8L * 1024 * 1024 // 8 MiB
        val scanWindow = 188 * 3 + 65536 // đủ dò 3 gói TS liên tiếp + biên an toàn, dùng cho file lớn

        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)

            if (!cdnRegex.containsMatchIn(request.url.toString())) {
                return@Interceptor response
            }

            val body = response.body ?: return@Interceptor response
            val declaredLength = body.contentLength()

            val fixedBody = if (declaredLength in 0 until fullBufferThreshold) {
                // Segment nhỏ (trường hợp phổ biến nhất: 1 đoạn .ts của HLS, dù đang xem
                // trực tiếp hay đang được downloader tải về từng phần) — buffer hết để
                // quét offset chính xác, không bỏ sót rác ở đầu.
                val bytes = body.bytes()
                val offset = findMpegTsOffset(bytes)
                val fixedBytes = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
                fixedBytes.toResponseBody(body.contentType())
            } else {
                // Không biết trước độ dài (chunked) hoặc file lớn: dùng cửa sổ quét giới hạn +
                // stream lười để không nạp hết vào RAM.
                val source = body.source()
                source.request(scanWindow.toLong())
                val available = source.buffer.size.coerceAtMost(scanWindow.toLong())
                val head = source.buffer.copy().readByteArray(available)
                val offset = findMpegTsOffset(head)
                if (offset > 0) {
                    source.skip(offset.toLong())
                }
                source.asResponseBody(body.contentType())
            }

            response.newBuilder()
                .body(fixedBody)
                .build()
        }
    }
    // ===================== Data classes (API models) =====================

    data class LoginRequest(
        val login: String,
        val password: String
    )

    data class LoginResponse(
        val access_token: String?,
        val refresh_token: String?
    )

    data class GenreInfo(
        val name: String?
    )

    data class CharacterInfo(
        val name: String?,
        val role: String?,
        val image_url: String?
    )

    data class Post(
        val id: Int,
        val title: String,
        val slug: String,
        val link: String,
        val poster: String?,
        val episodes: String?,
        val current_episode: String?,
        val type: String?,
        val year: String?
    )

    data class ApiFilterData(
        val posts: List<Post>? = null
    )

    data class ApiFilterResponse(
        val success: Boolean? = null,
        val message: String? = null,
        val data: ApiFilterData? = null
    )

    data class VideoItem(
        val url: String?
    )

    data class DetailPost(
        val id: Int,
        val title: String?,
        val description: String?,
        val poster: String?,
        val cover: String?,
        val type: String?,
        val year: String?,
        val genres: List<GenreInfo>?,
        val videos: List<VideoItem>?,
        val score: Double?,
        val characters: List<CharacterInfo>?
    )

    data class ApiDetailResponse(
        val data: DetailPost
    )

    data class EpisodeListItem(
        val id: Int,
        val number: Int?,
        val title: String?
    )

    data class EpisodeGroup(
        val name: String?,
        val episodes: List<EpisodeListItem>
    )

    data class EpisodeTeam(
        val team_name: String?,
        val groups: List<EpisodeGroup>
    )

    data class ApiEpisodeResponse(
        val teams: List<EpisodeTeam>
    )

    data class SubtitleItem(
        val file: String?,
        val label: String?
    )

    data class Stream(
        val url: String?,
        val server_name: String?,
        val player_type: String?,
        val subtitles: List<SubtitleItem>?
    )

    data class WatchAnimeInfo(
        val id: Int,
        val title: String?,
        val slug: String?,
        val thumbnail: String?
    )

    data class ApiWatchResponse(
        val id: Int?,
        val streams: List<Stream>?,
        val anime: WatchAnimeInfo?
    )

    data class RecommendationItem(
        val id: Int,
        val title: String?,
        val link: String?,
        val poster: String?,
        val type: String?,
        val year: String?,
        val episodes: String?,
        val current_episode: String?
    )

    data class ApiRecommendationResponse(
        val data: List<RecommendationItem>?
    )

    data class SearchItem(
        val id: Int,
        val title: String,
        val link: String,
        val image: String?,
        val type: String?,
        val episodes: String?,
        val current_episode: String?
    )

    data class ApiSearchResponse(
        val results: List<SearchItem>?,
        val has_more: Boolean?
    )
}
 
 
