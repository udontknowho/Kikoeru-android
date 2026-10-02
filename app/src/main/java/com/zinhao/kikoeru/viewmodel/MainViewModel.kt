package com.zinhao.kikoeru

import android.app.Application
import androidx.lifecycle.*
import androidx.recyclerview.widget.RecyclerView
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.lang.Thread.sleep

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App

    // ---- 可观察数据 ----
    private val _works = MutableLiveData<MutableList<JSONObject>>(mutableListOf())
    val works: LiveData<MutableList<JSONObject>> = _works

    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean> = _loading

    private val _title = MutableLiveData<String>()
    val title: LiveData<String> = _title

    private val _errorEvent = MutableLiveData<Throwable?>()
    val errorEvent: LiveData<Throwable?> = _errorEvent

    // 用于恢复滚动位置（一次性事件）
    private val _scrollToPosition = MutableLiveData<Int>()
    val scrollToPosition: LiveData<Int> = _scrollToPosition

    // ---- 持久化状态（通过 SharedPreferences） ----
    var type: Int
        get() = app.getValue(CONFIG_TYPE, TYPE_ALL_WORK.toLong()).toInt()
        set(value) = app.setValue(CONFIG_TYPE, value.toLong())

    var page: Int
        get() = app.getValue(CONFIG_PAGE, 1).toInt()
        set(value) = app.setValue(CONFIG_PAGE, value.toLong())

    var totalCount: Int
        get() = app.getValue(CONFIG_TOTAL, 0).toInt()
        set(value) = app.setValue(CONFIG_TOTAL, value.toLong())

    var currentPage: Int
        get() = app.getValue(CONFIG_CURRENT_PAGE, 1).toInt()
        set(value) = app.setValue(CONFIG_CURRENT_PAGE, value.toLong())

    var tagId: Int
        get() = app.getValue(CONFIG_PARAM_INT, -1).toInt()
        set(value) = app.setValue(CONFIG_PARAM_INT, value.toLong())

    var tagStr: String
        get() = app.getValue(CONFIG_PARAM_STR, "")
        set(value) = app.setValue(CONFIG_PARAM_STR, value)

    var vaId: String
        get() = app.getValue(CONFIG_PARAM_STR_VA_ID, "")
        set(value) = app.setValue(CONFIG_PARAM_STR_VA_ID, value)

    var vaName: String
        get() = app.getValue(CONFIG_PARAM_STR_VA_NAME, "")
        set(value) = app.setValue(CONFIG_PARAM_STR_VA_NAME, value)

    var circlesId: Long
        get() = app.getValue(CONFIG_PARAM_LONG_CIRCLES_ID, -1L)
        set(value) = app.setValue(CONFIG_PARAM_LONG_CIRCLES_ID, value)

    var circlesName: String
        get() = app.getValue(CONFIG_PARAM_STR_CIRCLES_NAME, "")
        set(value) = app.setValue(CONFIG_PARAM_STR_CIRCLES_NAME, value)

    var lastOpenTitle: String
        get() = app.getValue(CONFIG_PARAM_TITLE, application.getString(R.string.app_name))
        set(value) = app.setValue(CONFIG_PARAM_TITLE, value)

    var layoutType: Int
        get() = app.getValue(App.CONFIG_LAYOUT_TYPE, WorkAdapter.LAYOUT_STAGGERED.toLong()).toInt()
        set(value) = app.setValue(App.CONFIG_LAYOUT_TYPE, value.toLong())

    private var _layoutTypeLiveData = MutableLiveData<Int>(WorkAdapter.LAYOUT_STAGGERED)
    val layoutChangeLiveData: LiveData<Int> = _layoutTypeLiveData

    // 本次请求对应的类型：切 tab/筛选时用它丢弃过期响应
    private var requestType = TYPE_ALL_WORK
    // 请求还在飞时又来了新的加载请求，等它回来再拉一次
    private var pendingReload = false

    /**
     * 同步的“在途”标记。判断有没有请求在飞不能用 _loading.value：
     * postValue 是异步的，回调里紧接着读到的还是旧值，判断会错。
     */
    @Volatile
    private var inFlight = false

    // ---- 方法 ----

    fun clearWorks() {
        _works.postValue(arrayListOf())
        page = 1
        currentPage = 1
        totalCount = 0
    }

    fun loadFromNetwork() {
        app.debugLog("MainVM", "loadFromNetwork type=$type page=$page inFlight=$inFlight pendingReload=$pendingReload")
        if (inFlight) {
            pendingReload = true
            return
        }
        inFlight = true
        _loading.postValue(true)

        // 使用协程包装回调（避免阻塞主线程）
        viewModelScope.launch {
            // 由于 Api 方法是回调式的，我们无法直接用挂起函数，
            // 但可以在 IO 线程中发起请求，回调会回到主线程？
            // 实际上回调本身就在主线程执行（原代码未指定线程），
            // 所以可以直接在主线程调用，但为了统一，我们在 IO 线程模拟一下
            withContext(Dispatchers.IO) {
                // 实际上这里不能直接同步等待，但我们仍使用回调方式
                // 真正的做法：将回调转换为挂起函数，但为了最小改动，我们保持回调风格
                if(BuildConfig.DEBUG){
                    sleep(300)
                }
            }
            // 直接在主线程调用原来的 Api 方法（它们内部会异步执行）
            requestType = type
            performRequest()
        }
    }

    val callback = object : AsyncHttpClient.JSONObjectCallback() {
        override fun onCompleted(e: Exception?, response: AsyncHttpResponse?, jsonObject: JSONObject?) {
            inFlight = false
            _loading.postValue(false)
            // 期间切了 tab/筛选，或又排了一次加载：本次结果作废
            val discard = pendingReload || requestType != type
            app.debugLog(
                "MainVM",
                "onCompleted code=${response?.code()} err=${e?.message} discard=$discard " +
                        "type=$type reqType=$requestType 现有=${_works.value?.size ?: 0}"
            )
            if (pendingReload) {
                pendingReload = false
                loadFromNetwork()
            } else if (discard && _works.value.isNullOrEmpty()) {
                // 结果作废、列表空着、又没有别的请求在飞：不补这一次的话
                // 页面就永久空着（切账号后一直刷不出来就是这个）
                loadFromNetwork()
            }
            if (discard) return
            if (e != null) {
                _errorEvent.postValue(e)
                return
            }
            if (response == null || response.code() != 200) {
                // 可能是本地缓存
            }
            try {
                jsonObject?.let { obj ->
                    val fromNetWorkArray = obj.getJSONArray("works")
                    totalCount = obj.getJSONObject("pagination").getInt("totalCount")
                    currentPage = page
                    page = obj.getJSONObject("pagination").getInt("currentPage") + 1
                    if (fromNetWorkArray.length() > 0) {
                        page = minOf(page, totalCount / fromNetWorkArray.length() + 1)
                    }
                    // 更新标题
                    _title.postValue("${computeTitle()} ($totalCount)")
                    app.debugLog("MainVM", "解析成功 works=${fromNetWorkArray.length()} total=$totalCount page=$page")
                    if(fromNetWorkArray.length() > 0) {
                        appendWorks(fromNetWorkArray)
                    }
                }
            } catch (je: JSONException) {
                app.debugLog("MainVM", "解析失败: $je")
                _errorEvent.postValue(je)
            }
        }
    }

    private fun performRequest() {
        when (requestType) {
            TYPE_ALL_WORK -> Api.doGetWorks(page, callback)
            TYPE_SELF_LISTENING -> Api.doGetReview(Api.FILTER_LISTENING, page, callback)
            TYPE_SELF_LISTENED -> Api.doGetReview(Api.FILTER_LISTENED, page, callback)
            TYPE_SELF_MARKED -> Api.doGetReview(Api.FILTER_MARKED, page, callback)
            TYPE_SELF_REPLAY -> Api.doGetReview(Api.FILTER_REPLAY, page, callback)
            TYPE_SELF_POSTPONED -> Api.doGetReview(Api.FILTER_POSTPONED, page, callback)
            TYPE_TAG_WORK -> Api.doGetWorksByTag(page, tagId, callback)
            TYPE_SELF_REVIEW -> Api.doGetReview(null, page, callback)
            TYPE_VA_WORK -> Api.doGetWorkByVa(page, vaId, callback)
            TYPE_CIRCLES_WORK -> Api.doGetWorkByCircles(page, circlesId, callback)
            TYPE_LOCAL_WORK -> loadLocalWorks()
        }
    }

    private fun loadLocalWorks() {
        try {
            LocalFileCache.getInstance().readLocalDownloadWorks(object : AsyncHttpClient.JSONObjectCallback() {
                override fun onCompleted(e: Exception?, response: AsyncHttpResponse?, jsonObject: JSONObject?) {
                    inFlight = false
                    _loading.postValue(false)
                    if (pendingReload) {
                        pendingReload = false
                        loadFromNetwork()
                    }
                    if (e != null) {
                        _errorEvent.postValue(e)
                        return
                    }
                    jsonObject?.let {
                        try {
                            val formLocalArray = it.getJSONArray("works")
                            totalCount = formLocalArray.length()
                            appendWorks(formLocalArray)
                            _title.postValue("${computeTitle()} ($totalCount)")
                        } catch (je: JSONException) {
                            _errorEvent.postValue(je)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            inFlight = false
            _errorEvent.postValue(e)
            _loading.postValue(false)
        }
    }

    fun loadLastOpenWorks() {
        // 只有首页(全部作品)才有“上次的内容”可恢复；筛选状态(标签/声优/社团/进度)不能顶着首页恢复
        if (type != TYPE_ALL_WORK) {
            resetFilter()
            _title.postValue(computeTitle())
            loadFromNetwork()
            return
        }
        try {
            LocalFileCache.getInstance().readLastOpenWorks(object : AsyncHttpClient.JSONArrayCallback() {
                override fun onCompleted(e: Exception?, response: AsyncHttpResponse?, jsonArray: JSONArray?) {
                    app.debugLog("MainVM", "读上次缓存 err=${e?.message} 条数=${jsonArray?.length() ?: -1}")
                    if (e != null) {
                        // 缓存读取失败，从网络加载
                        loadFromNetwork()
                        return
                    }
                    // 空数组不算有效缓存：否则白屏会被当成“上次的内容”永久恢复，
                    // 之后再也不会请求网络
                    jsonArray?.takeIf { it.length() > 0 }?.let {
                        appendWorks(it)
                        _title.postValue(lastOpenTitle)
                        _loading.postValue(false)
                        // 恢复滚动位置
                        val pos = app.getValue(CONFIG_PARAM_POSITION, 0).toInt()
                        if (pos != RecyclerView.NO_POSITION) {
                            _scrollToPosition.postValue(pos)
                        }
                    } ?: run {
                        loadFromNetwork()
                    }
                }
            })
        } catch (e: Exception) {
            loadFromNetwork()
        }
    }

    private fun appendWorks(jsonArray: JSONArray) {
        // 创建新列表，避免同一个对象引用导致 LiveData 不通知
        val currentList = _works.value?.toMutableList() ?: mutableListOf()

        for (i in 0 until jsonArray.length()) {
            try {
                currentList.add(jsonArray.getJSONObject(i))
            } catch (e: JSONException) {
                _errorEvent.postValue(e)
            }
        }
        _works.postValue(currentList) // 发射新列表，观察者一定会收到
    }

    /**
     * “换一批”：从随机一页开始拉。
     * 服务端的 seed 参数对 /api/works 是无效的（实测换 seed 结果完全一样），
     * 所以只能换页码 —— 每页 20 条，总页数 = totalCount / 20。
     */
    fun shuffleStart() {
        val pageSize = 20
        val total = totalCount
        val maxPage = if (total > pageSize) total / pageSize else 1
        clearWorks()   // 会把 page/currentPage/totalCount 归成 1/1/0
        val randomPage = (1..maxPage).random()
        page = randomPage
        currentPage = randomPage
    }

    /** 清掉全部筛选，回到首页(全部作品) */
    fun resetFilter() {
        type = TYPE_ALL_WORK
        tagId = -1
        tagStr = ""
        vaId = ""
        vaName = ""
        circlesId = -1L
        circlesName = ""
    }

    private fun computeTitle(): String {
        return when (type) {
            TYPE_ALL_WORK -> getApplication<Application>().getString(R.string.app_name)
            TYPE_SELF_LISTENING -> getApplication<Application>().getString(R.string.listening)
            TYPE_SELF_LISTENED -> getApplication<Application>().getString(R.string.listened)
            TYPE_SELF_MARKED -> getApplication<Application>().getString(R.string.marked)
            TYPE_SELF_REPLAY -> getApplication<Application>().getString(R.string.replay)
            TYPE_SELF_POSTPONED -> getApplication<Application>().getString(R.string.postponed)
            TYPE_TAG_WORK -> tagStr
            TYPE_SELF_REVIEW -> getApplication<Application>().getString(R.string.review)
            TYPE_VA_WORK -> vaName
            TYPE_CIRCLES_WORK -> circlesName
            TYPE_LOCAL_WORK -> {
                if (app.isSaveExternal()) "外部公共目录" else "内部私有目录"
            }
            else -> "--"
        }
    }

    fun saveState() {
        // 保存 works 列表到本地缓存
        try {
            val jsonArray = JSONArray()
            _works.value?.forEach { jsonArray.put(it) }
            LocalFileCache.getInstance().saveLastOpenWorks(jsonArray)
            lastOpenTitle =title.value?:application.getString(R.string.app_name)
        } catch (e: IOException) {
            _errorEvent.postValue(e)
        }
    }

    fun setLastPosition(pos: Int) {
        app.setValue(CONFIG_PARAM_POSITION, pos.toLong())
    }

    fun changeLayoutType(layoutType: Int) {
        _layoutTypeLiveData.postValue(layoutType)
        this.layoutType = layoutType
    }

    companion object {
        // 常量（与原 Activity 保持一致）
        const val TYPE_ALL_WORK = 491
        const val TYPE_SELF_LISTENING = 492
        const val TYPE_SELF_LISTENED = 493
        const val TYPE_SELF_MARKED = 494
        const val TYPE_SELF_REPLAY = 495
        const val TYPE_SELF_POSTPONED = 496
        const val TYPE_TAG_WORK = 497
        const val TYPE_LOCAL_WORK = 498
        const val TYPE_VA_WORK = 499
        const val TYPE_CIRCLES_WORK = 500
        const val TYPE_SELF_REVIEW = 501

        // SharedPreferences keys（补充一些原来缺失的）
        private const val CONFIG_TYPE = "last_type"
        private const val CONFIG_PAGE = "last_page"
        private const val CONFIG_TOTAL = "total_count"
        private const val CONFIG_CURRENT_PAGE = "current_page"
        private const val CONFIG_PARAM_INT = "last_param_int"
        private const val CONFIG_PARAM_STR = "last_param_str"
        private const val CONFIG_PARAM_STR_VA_ID = "last_param_va_id"
        private const val CONFIG_PARAM_STR_VA_NAME = "last_param_va_name"
        private const val CONFIG_PARAM_LONG_CIRCLES_ID = "last_param_circles_id"
        private const val CONFIG_PARAM_STR_CIRCLES_NAME = "last_param_circles_name"
        private const val CONFIG_PARAM_TITLE = "last_param_title"
        private const val CONFIG_PARAM_POSITION = "last_open_work_position"
    }
}