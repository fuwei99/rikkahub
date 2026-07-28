package me.rerere.rikkahub.data.sync.r2

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.FileSystem
import java.io.IOException

/**
 * Coil 的 r2:// 协议加载器（P3）：现签现用，无任何无签名公网路径。
 *
 * r2://<acctUuid>/<key> → R2MediaStore.presign（TTL 3600s，带内存缓存）→ OkHttp 拉取。
 * Coil 自带的内存/磁盘缓存以 r2:// 字符串为 key，命中时不走网络。
 */
class R2ImageFetcher(
    private val ref: R2Ref,
    private val store: R2MediaStore,
    private val okHttpClient: OkHttpClient,
    private val context: Context,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val url = store.presign(ref).getOrThrow()
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("R2 fetch failed: HTTP ${response.code}")
            val body = response.body
            val buffer = Buffer().apply { body.source().readAll(this) }
            SourceFetchResult(
                source = ImageSource(source = buffer, fileSystem = FileSystem.SYSTEM),
                mimeType = body.contentType()?.toString(),
                dataSource = DataSource.NETWORK,
            )
        }
    }

    class Factory(
        private val store: R2MediaStore,
        private val okHttpClient: OkHttpClient,
    ) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ref = R2Ref.parse(data) ?: return null
            return R2ImageFetcher(ref, store, okHttpClient, options.context)
        }
    }
}
