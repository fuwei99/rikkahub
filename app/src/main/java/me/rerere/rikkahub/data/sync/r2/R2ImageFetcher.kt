package me.rerere.rikkahub.data.sync.r2

import android.content.Context
import coil3.ImageLoader
import coil3.Uri
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
 * Coil 自带的内存/磁盘缓存以 r2:// 字符串为 key（UriKeyer = uri.toString()），命中时不走网络。
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

    /**
     * 注意这里必须是 Fetcher.Factory<coil3.Uri> 而不是 <String>。
     *
     * Coil3 的 EngineInterceptor 会先跑 mappers 再找 fetcher，而内置的 StringMapper
     * 把所有 String model 无条件转成 coil3.Uri。所以 Factory<String> 的 type 检查
     * (String::class.isInstance(coil3.Uri)) 永远为 false —— 注册了也不会被调用，
     * r2:// 会一路漏到底层 fetcher 全部拒绝，最后抛 "Unable to create a fetcher that supports"。
     */
    class Factory(
        private val store: R2MediaStore,
        private val okHttpClient: OkHttpClient,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ref = R2Ref.parse(data.toString()) ?: return null
            return R2ImageFetcher(ref, store, okHttpClient, options.context)
        }
    }
}
