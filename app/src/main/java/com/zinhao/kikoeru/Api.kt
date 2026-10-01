package com.zinhao.kikoeru

import android.net.Proxy
import android.net.Uri
import androidx.annotation.StringDef
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpClient.JSONArrayCallback
import com.koushikdutta.async.http.AsyncHttpClient.JSONObjectCallback
import com.koushikdutta.async.http.AsyncHttpRequest
import com.koushikdutta.async.http.body.JSONObjectBody
import com.zinhao.kikoeru.network.HttpClientManager
import com.zinhao.kikoeru.network.LoggingInterceptor
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.*
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException

object Api {
    private var HOST = "http://localhost:8888"
    private const val  TAG = "Api"
    const val REMOTE_HOST: String = "https://api.asmr.one"
    const val LOCAL_HOST: String = "http://localhost:8888"
    @JvmField
    var authorization: String = ""
    @JvmField
    var token: String = ""
    private var subtitle = 1
    private var sort = 1
    private var order = "id"

    /** 内置站点（登录页下拉、账号页「内置站点」共用） */
    val BUILTIN_HOSTS = arrayOf(
        "https://api.asmr.one",
        "https://asmr.unikon.art",
        "https://asmr.emoe.top",
        "https://asmr.homes"
    )

    /**
     * 服务器类型：
     * - NORMAL：正常账号（asmr.one）
     * - GUEST_TOKEN：只用 POST guest/guest 拿 token（asmr.homes）
     * - NO_TOKEN：接口本身不要 token，POST 会被 Cloudflare 拦，本地建 guest 用户就行（unikon / emoe.top）
     */
    enum class HostKind { NORMAL, GUEST_TOKEN, NO_TOKEN }

    private val GUEST_TOKEN_HOSTS = listOf("asmr.homes")
    private val NO_TOKEN_HOSTS = listOf("asmr.unikon.art", "asmr.emoe.top")

    @JvmStatic
    fun hostKind(host: String?): HostKind {
        if (host.isNullOrEmpty()) return HostKind.NORMAL
        if (NO_TOKEN_HOSTS.any { host.contains(it, ignoreCase = true) }) return HostKind.NO_TOKEN
        if (GUEST_TOKEN_HOSTS.any { host.contains(it, ignoreCase = true) }) return HostKind.GUEST_TOKEN
        return HostKind.NORMAL
    }

    /** 需要 token 才能用，但游客不能用 POST 拿（unikon / emoe.top） */
    @JvmStatic
    fun isNoTokenHost(host: String?): Boolean = hostKind(host) == HostKind.NO_TOKEN

    /**
     * “个人库”这一类实例（不是 asmr.one 那套）：排序字段叫 created_at、搜索走查询参数、支持 random
     */
    @JvmStatic
    fun isPersonalHost(host: String?): Boolean = hostKind(host) != HostKind.NORMAL

    /** 当前连接的服务器地址 */
    @JvmStatic
    fun currentHost(): String = HOST

    /**
     * 规整服务器地址：用户很容易把网页地址一起粘进来（比如 https://asmr.unikon.art/works），
     * 而接口根在主机上，带上路径会变成 .../works/api/works → 404。
     */
    @JvmStatic
    fun normalizeHost(host: String): String {
        var h = host.trim()
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            // 自建服务器多半是 http + 端口，公网域名默认走 https
            val looksLocal = h.startsWith("localhost") || h.startsWith("127.") ||
                    h.startsWith("192.168.") || h.startsWith("10.") || h.contains(":")
            h = (if (looksLocal) "http://" else "https://") + h
        }
        // 只削掉网页路由后缀，别把自建服务器部署在子路径的情况弄坏
        h = h.replace(
            Regex("/(works|tags|vas|circles|playlists|favourites|progress|search|about)(/.*)?$", RegexOption.IGNORE_CASE),
            ""
        )
        return h.trimEnd('/')
    }

    /** 排序字段：个人库实例不认 create_date（直接 400），它叫 created_at */
    private fun orderParam(): String =
        if (isPersonalHost(HOST) && order == "create_date") "created_at" else order

    private val okHttpClient: OkHttpClient = HttpClientManager.getPacEnabledClient()

    @JvmStatic
    fun init(tokenStr: String, host: String) {
        token = tokenStr
        authorization = String.format("Bearer %s", tokenStr)
        HOST = normalizeHost(host)
        subtitle = App.getInstance().getValue(App.CONFIG_ONLY_DISPLAY_LRC, 1).toInt()
        order = App.getInstance().getValue(App.CONFIG_ORDER, "id")
        sort = App.getInstance().getValue(App.CONFIG_SORT, 0).toInt()
    }

    private fun makeSort(): String {
        if (sort != 1) {
            return "desc"
        } else {
            return "asc"
        }
    }

    @JvmStatic
    fun setOrder(order: String) {
        if (Api.order == order) {
            if (sort == 1) {
                sort = 0
            } else {
                sort = 1
            }
            App.getInstance().setValue(App.CONFIG_SORT, sort.toLong())
            return
        }
        Api.order = order
        // 换排序维度时方向回到默认（倒序）。
        // 不重置的话会一直停在之前翻出来的升序上，看起来就像“回不到默认”。
        sort = 0
        App.getInstance().setValue(App.CONFIG_SORT, sort.toLong())
        App.getInstance().setValue(App.CONFIG_ORDER, order)
    }

    /** 当前排序维度，给菜单显示用 */
    @JvmStatic
    fun currentOrder(): String = order

    /** 当前是否升序（默认倒序） */
    @JvmStatic
    fun isAscending(): Boolean = sort == 1

    @JvmStatic
    fun setSubtitle(subtitle: Int) {
        Api.subtitle = subtitle
    }

    private fun okhttpGetJsonObject(url: String, callback: JSONObjectCallback) {
        val request = Request.Builder().get().url(url).addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Android Application <Kikoeru>")
            .addHeader("authorization", authorization)
            .build()
        okHttpClient.newCall(request).enqueue(object :Callback{
            override fun onFailure(call: Call, e: IOException) {
                callback.onCompleted(e, LocalResponse(404), JSONObject("{}"))
            }

            override fun onResponse(call: Call, response: Response) {
                if(response.isSuccessful){
                    val body = response.body?.string()
                    callback.onCompleted(null, LocalResponse(response.code), JSONObject(body?:""))
                }else{
                    callback.onCompleted(null, LocalResponse(response.code), JSONObject("{}"))
                }
            }
        })
    }

    private fun okhttpPutJsonObject(url: String, data: JSONObject, callback: JSONObjectCallback) {
        val request = Request.Builder().put(data.toString().toRequestBody("application/json;charset=utf-8".toMediaType()))
            .url(url).addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Android Application <Kikoeru>")
            .addHeader("authorization", authorization)
            .build()
        okHttpClient.newCall(request).enqueue(object :Callback{
            override fun onFailure(call: Call, e: IOException) {
                callback.onCompleted(e, LocalResponse(404), JSONObject("{}"))
            }

            override fun onResponse(call: Call, response: Response) {
                if(response.isSuccessful){
                    val body = response.body?.string()
                    callback.onCompleted(null, LocalResponse(response.code), JSONObject(body?:""))
                } else{
                    callback.onCompleted(null, LocalResponse(response.code), JSONObject("{}"))
                }
            }
        })
    }

    private fun okhttpGetJsonArray(url: String, callback: JSONArrayCallback) {
        val request = Request.Builder().get().url(url).addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Android Application <Kikoeru>")
            .addHeader("authorization", authorization)
            .build()
        okHttpClient.newCall(request).enqueue(object :Callback{
            override fun onFailure(call: Call, e: IOException) {
                callback.onCompleted(e, LocalResponse(404), JSONArray("[]"))
            }

            override fun onResponse(call: Call, response: Response) {
                if(response.isSuccessful){
                    val body = response.body?.string()
                    callback.onCompleted(null, LocalResponse(response.code), JSONArray(body))
                }else{
                    callback.onCompleted(null, LocalResponse(response.code), JSONArray("[]"))
                }
            }
        })
    }

    private fun okhttpGetString(url: String, callback: AsyncHttpClient.StringCallback) {
        val request = Request.Builder().get().url(url).addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Android Application <Kikoeru>")
            .addHeader("authorization", authorization)
            .build()
        okHttpClient.newCall(request).enqueue(object :Callback{
            override fun onFailure(call: Call, e: IOException) {
                callback.onCompleted(e, LocalResponse(404), null)
            }

            override fun onResponse(call: Call, response: Response) {
                if(response.isSuccessful){
                    callback.onCompleted(null, LocalResponse(response.code), response.body?.string())
                }else{
                    callback.onCompleted(null, LocalResponse(response.code), response.body?.string())
                }
            }
        })
    }
    @JvmStatic
    fun doGetWorks(page: Int, callback: JSONObjectCallback?) {
        // seed 参数对 /api/works 无效（实测换 seed 结果一模一样），“换一批”靠换页码，见 MainViewModel.shuffleStart()
        val url = "${HOST}/api/works?order=${orderParam()}&sort=${makeSort()}&page=${page}&seed=35&subtitle=${subtitle}"
        callback?.let {
            okhttpGetJsonObject(url,it)
        }
    }
    @JvmStatic
    fun doGetWorksByTag(page: Int, tagId: Int, callback: JSONObjectCallback?) {
        val url = "${HOST}/api/tags/${tagId}/works?order=${orderParam()}&sort=${makeSort()}&page=${page}&seed=21&subtitle=${subtitle}"
        callback?.let {
            okhttpGetJsonObject(url,it)
        }
    }

    @JvmStatic
    fun doGetWorkByVa(page: Int, vaId: String, callback: JSONObjectCallback?) {
//        http://localhost:8888/api/vas/2b5e7ab5-d994-5491-a53c-f1b6ae562d0e/works?order=price&sort=desc&page=1&seed=68
        val url = HOST +  "/api/vas/${vaId}/works?order=${orderParam()}&sort=${makeSort()}&page=${page}&seed=21&subtitle=${subtitle}"
        callback?.let {
            okhttpGetJsonObject(url,it)
        }
    }

    @JvmStatic
    fun doGetWorkByCircles(page: Int, circlesId: Long, callback: JSONObjectCallback?) {
        //    http://localhost:8980/api/circles/54978/works?order=release&sort=desc&page=1&seed=59
        val url = "${HOST}/api/circles/${circlesId}/works?order=${orderParam()}&sort=${makeSort()}&page=${page}&seed=21&subtitle=${subtitle}"
        callback?.let {
            okhttpGetJsonObject(url,it)
        }
    }

    @JvmStatic
    fun doGetAllTags(callback: JSONArrayCallback?) {
        val url = "$HOST/api/tags/"
        callback?.let {
            okhttpGetJsonArray(url,it)
        }
    }

    @JvmStatic
    fun doGetAllVas(callback: JSONArrayCallback?) {
        val url = "$HOST/api/vas/"
        callback?.let {
            okhttpGetJsonArray(url,it)
        }
    }

    @JvmStatic
    fun doGetDocTree(id: Int, callback: JSONArrayCallback?) {
        val url = "$HOST/api/tracks/${id}"
        callback?.let {
            okhttpGetJsonArray(url,it)
        }
    }

    @JvmStatic
    fun doGetWork(keyword: String, page: Int, callback: JSONObjectCallback?) {
        // 免账号实例的搜索是查询参数形式：/api/search?keyword=xxx（路径形式会 404）
        val url = if (isPersonalHost(HOST)) {
            val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
            "${HOST}/api/search?keyword=${kw}&order=${orderParam()}&sort=${makeSort()}&page=${page}"
        } else {
            "${HOST}/api/search/${keyword}?order=${orderParam()}&sort=${makeSort()}&page=${page}&seed=18&subtitle=0"
        }
        callback?.let {
            okhttpGetJsonObject(url,it)
        }
    }
    @JvmStatic
    fun checkLrc(hash: String, callback: JSONObjectCallback?) {
        val url = "${HOST}/api/media/check-lrc/${hash}?token=${token}"
        callback?.let {
            okhttpGetJsonObject(url,it)
        }
    }

    @JvmStatic
    fun doGetMediaString(hash: String, callback: AsyncHttpClient.StringCallback?) {
        val url = "${HOST}/api/media/stream/${hash}?token=$token"
        callback?.let {
            okhttpGetString(url,it)
        }
    }

    @JvmStatic
    fun doGetToken(userName: String?, password: String?, host: String?, callback: JSONObjectCallback?) {
        val pwd = JSONObject()
        try {
            pwd.put("name", userName)
            pwd.put("password", password)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val requestBody: RequestBody = pwd.toString().toRequestBody("application/json;charset=utf-8".toMediaType())
        val request = Request.Builder().post(requestBody)
            .url("$host/api/auth/me")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Android Application <Kikoeru>")
            .build()
        okHttpClient.newCall(request).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback?.onCompleted(e, LocalResponse(404), null)
            }

            override fun onResponse(call: Call, response: Response) {
                if(response.isSuccessful){
                    response.body?.string()?.let {
                        callback?.onCompleted(null, LocalResponse(response.code), JSONObject(it))
                    }
                }else{
                    callback?.onCompleted(null, LocalResponse(response.code), null)
                }
            }

        })
    }

    const val FILTER_MARKED: String = "marked"
    const val FILTER_LISTENING: String = "listening"
    const val FILTER_LISTENED: String = "listened"
    const val FILTER_REPLAY: String = "replay"
    const val FILTER_POSTPONED: String = "postponed"

    /**
     * GET
     * [...](https://api.asmr.one/api/review?order=id&sort=desc&page=1&filter=marked)      我的进度 - 想听
     * [...](https://api.asmr.one/api/review?order=id&sort=desc&page=1&filter=listening)   我的进度 - 在听
     * [...](https://api.asmr.one/api/review?order=id&sort=desc&page=1&filter=listened)    我的进度 - 听过
     * [...](https://api.asmr.one/api/review?order=id&sort=desc&page=1&filter=replay)      我的进度 - 重听
     * [...](https://api.asmr.one/api/review?order=id&sort=desc&page=1&filter=postponed)   我的进度 - 搁置
     * [...](https://api.asmr.one/api/review?order=id&sort=desc&page=1)                    我的评价
     */
    @JvmStatic
    fun doGetReview(@Filter filter: String?, page: Int, callback: JSONObjectCallback?) {
        // filter 为空 = “我的评价”（站点 /favourites 的 review 模式）；拼成 &filter=null 会被服务端当成非法值
        val filterQuery = if (filter.isNullOrBlank()) "" else "&filter=$filter"
        val url = "$HOST/api/review?order=${order}&sort=${makeSort()}&page=${page.coerceAtLeast(1)}$filterQuery"
        callback?.let {
            okhttpGetJsonObject(url,it)
        }
    }

    /***
     * http://localhost:8980/api/circles/
     * @param callback
     */
    @JvmStatic
    fun doGetCirclesList(callback: JSONArrayCallback?) {
        val url = "${HOST}/api/circles/"
        callback?.let {
            okhttpGetJsonArray(url,it)
        }
    }

    /**
     * PUT
     * 标记在听 [...](https://api.asmr.one/api/review?starOnly=false&progressOnly=true)
     * data:   {"user_name":"guest","work_id":380205,"progress":"listening"}
     * result: 200: {message: "更新进度成功"}
     */
    @JvmStatic
    fun doPutReview(id: Long, @Filter progress: String?, callback: JSONObjectCallback?) {
        val url = "${HOST}/api/review?starOnly=false&progressOnly=true"
        val jsonObject = JSONObject()
        val userName = App.getInstance().currentUser()?.getName() ?: "guest"
        try {
            jsonObject.put("user_name", userName)
            jsonObject.put("work_id", id)
            jsonObject.put("progress", progress)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
       callback?.let {
           okhttpPutJsonObject(url,jsonObject,it)
       }
    }

    @JvmStatic
    fun formatGetUrl(path: String, useToken: Boolean): String {
        if (path.startsWith("http")) {
            if (useToken) {
                return String.format("%s?token=%s", path, token)
            } else {
                return path
            }
        } else {
            if (useToken) {
                return String.format("%s%s?token=%s", HOST, path, token)
            } else {
                return String.format("%s%s", HOST, path)
            }
        }
    }

    /**
     * 当前用户的 host。服务刚启动/恢复上次播放列表时 allUsers 还没加载完，
     * currentUser() 会是 null，以前直接 .getHost() 就崩（崩溃日志里的 NPE 就是这个）。
     */
    @JvmStatic
    fun hostOrDefault(): String =
        App.getInstance().currentUser()?.getHost() ?: HOST

    @JvmStatic
    fun minCoverImageUrl(rjNumber: Long): String {
        return (hostOrDefault()
                + String.format("/api/cover/%d?type=sam&token=%s", rjNumber, token))
    }

    @JvmStatic
    fun fullCoverImageUrl(rjNumber: Long): String {
        return (hostOrDefault()
                + String.format("/api/cover/%d?token=%s", rjNumber, token))
    }

    @Retention(AnnotationRetention.SOURCE)
    @StringDef(value = [FILTER_MARKED, FILTER_LISTENING, FILTER_LISTENED, FILTER_REPLAY, FILTER_POSTPONED])
    annotation class Filter
}
