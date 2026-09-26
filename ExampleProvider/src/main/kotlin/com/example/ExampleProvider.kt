package com.example
    
    import com.lagradost.cloudstream3.*
    import com.lagradost.cloudstream3.utils.ExtractorLink
    import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
            
            val results = document.select("a[href^=/show/], a[href^=/title/]").mapNotNull {
                val href = it.attr("href")
                val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
                val poster = it.selectFirst("img")?.attr("src")
                
                newTvSeriesSearchResponse(title, href, TvType.Cartoon) {
                    this.posterUrl = poster
                }
            }
            return results.distinctBy { it.url }
        }
    
        // 2. Load Show/Movie Details & Episodes
        override suspend fun load(url: String): LoadResponse? {
            val document = app.get(url).document
            val title = document.selectFirst("h1")?.text() ?: return null
            val poster = document.selectFirst("img")?.attr("src")
            
            val description = document.select("p").firstOrNull { it.text().length > 20 }?.text()
    
            val episodes = mutableListOf<Episode>()
            
            val seasonLinks = document.select("a[href^=$url/]").map { it.attr("href") }.distinct()
    
            if (seasonLinks.isNotEmpty()) {
                for (seasonLink in seasonLinks) {
                    val seasonNum = seasonLink.substringAfterLast("/").toIntOrNull()
                    val seasonDoc = app.get(fixUrl(seasonLink)).document
                    
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
	   val html = app.get(data).text
            
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
    			type = ExtractorLinkType.M3U8
    			)
		)
                return true
            }
            return false
	}
    }
