package com.example
    
    import com.lagradost.cloudstream3.*
    import com.lagradost.cloudstream3.utils.ExtractorLink
    import com.lagradost.cloudstream3.utils.Qualities
    
    class ToonixProvider : MainAPI() {
        override var mainUrl = "https://toonix.bond"
        override var name = "Toonix"
        override val hasMainPage = true
        override var lang = "en"
        override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)
    
        // 1. Search Functionality
        override suspend fun search(query: String): List<SearchResponse> {
            val document = app.get("$mainUrl/search?q=$query").document
            
            // Extract shows/movies from the search page
            val results = document.select("a[href^=/show/], a[href^=/title/]").mapNotNull {
                val href = it.attr("href")
                val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
                val poster = it.selectFirst("img")?.attr("src")
                
                newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
                    this.posterUrl = poster
                }
            }
            // Remove duplicates just in case
            return results.distinctBy { it.url }
        }
    
        // 2. Load Show/Movie Details & Episodes
        override suspend fun load(url: String): LoadResponse? {
            val document = app.get(url).document
            val title = document.selectFirst("h1")?.text() ?: return null
            val poster = document.selectFirst("img")?.attr("src")
            
            // Find the longest paragraph to use as the description
            val description = document.select("p").firstOrNull { it.text().length > 20 }?.text()
    
            val episodes = mutableListOf<Episode>()
            
            // Find links that look like seasons (e.g., /show/doraemon/1)
            val seasonLinks = document.select("a[href^=$url/]").map { it.attr("href") }.distinct()
    
            if (seasonLinks.isNotEmpty()) {
                // It's a TV show with seasons. Let's fetch the episodes for each season.
                for (seasonLink in seasonLinks) {
                    val seasonNum = seasonLink.substringAfterLast("/").toIntOrNull()
                    val seasonDoc = app.get(fixUrl(seasonLink)).document
                    
                    // Find links that look like episodes (e.g., /show/doraemon/1/1)
                    val episodeLinks = seasonDoc.select("a[href^=$seasonLink/]").map { it.attr("href") }.distinct()
                    
                    for (epLink in episodeLinks) {
                        val epNum = epLink.substringAfterLast("/").toIntOrNull()
                        episodes.add(newEpisode(epLink) {
                            this.name = "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            } else {
                // It's a Movie or Single item (no seasons found)
                episodes.add(newEpisode(url) {
                    this.name = "Watch"
                })
            }
    
            return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    
        // 3. Extract the Video Stream Link
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            // Fetch the specific episode page (e.g., /show/doraemon/1/1)
            val html = app.get(data).text
            
            // Toonix hides its m3u8 streams inside the Next.js page source under a Cloudflare worker URL.
            // This regex looks for it.
            val m3u8Regex = Regex("""(https://v2\.hlsfastnet\.workers\.dev/[^"'\s\\]+)""")
            val match = m3u8Regex.find(html)
    
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }
            return false
        }
    }
