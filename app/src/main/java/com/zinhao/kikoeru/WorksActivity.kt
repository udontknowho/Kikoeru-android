package com.zinhao.kikoeru

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.addCallback
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.app.ActivityOptionsCompat
import androidx.core.graphics.Insets
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.*
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpResponse
import com.zinhao.kikoeru.Api.setOrder
import com.zinhao.kikoeru.databinding.ActivityMainBinding
import com.zinhao.kikoeru.ui.WorkPageActivity
import com.zinhao.kikoeru.utils.LoadingFooterDecoration
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.absoluteValue
import kotlin.math.max

class WorksActivity : BaseActivity(), MusicChangeListener, ServiceConnection, TagsView.TagClickListener<JSONObject?> {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private var workAdapter: WorkAdapter? = null
    private var ctrlBinder: AudioService.CtrlBinder? = null

    // 底部播放栏控件
    private lateinit var ivCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvWorkTitle: TextView
    private lateinit var ibStatus: ImageButton
    private lateinit var ibFloatLrcWindow: ImageButton

    // 动画
    private var outAnim: Animation? = null
    private var inAnim: Animation? = null
    private var shouldShowAnim = true

    // 底栏当前 tab
    private var currentTab = TAB_HOME

    // 收藏 tab 里选中的状态（默认“我的评价”）
    private var favType = MainViewModel.TYPE_SELF_REVIEW

    /** 收藏 tab 的 chip → 列表类型 */
    private val chipTypeMap = linkedMapOf(
        R.id.chipReview to MainViewModel.TYPE_SELF_REVIEW,
        R.id.chipMarked to MainViewModel.TYPE_SELF_MARKED,
        R.id.chipListening to MainViewModel.TYPE_SELF_LISTENING,
        R.id.chipListened to MainViewModel.TYPE_SELF_LISTENED,
        R.id.chipReplay to MainViewModel.TYPE_SELF_REPLAY,
        R.id.chipPostponed to MainViewModel.TYPE_SELF_POSTPONED,
        R.id.chipLocal to MainViewModel.TYPE_LOCAL_WORK
    )

    private var loadingDecoration: LoadingFooterDecoration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.getInstance().isUseNewLayout) {
            startActivity(Intent(this, WorkPageActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setSupportActionBar(binding.toolbar)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupViews()
        setupListeners()
        observeViewModel()

        // 启动并绑定音频服务
        startForegroundService(Intent(this, AudioService::class.java))
        bindService(Intent(this, AudioService::class.java), this, BIND_AUTO_CREATE)

        // 初始化圆圈列表映射
        Api.doGetCirclesList(object : AsyncHttpClient.JSONArrayCallback() {
            override fun onCompleted(e: Exception?, asyncHttpResponse: AsyncHttpResponse?, jsonArray: JSONArray) {
                if (e != null) {
                    App.getInstance().alertException(e)
                    return
                }
                try {
                    App.getInstance().initCirclesIdMap(jsonArray)
                } catch (ex: JSONException) {
                    throw RuntimeException(ex)
                }
            }
        })

        // 加载上次打开的作品
        viewModel.loadLastOpenWorks()
    }

    // ==================== 视图初始化 ====================

    private fun setupViews() {
        setSafeArea(binding.appBarLayout,object : BaseActivity.InsetReady{
            override fun onInsetReady(insets: Insets) {
                binding.recyclerView.setPadding(insets.left, 0, insets.right, 0)
                binding.swipe.setPadding(insets.left, 0, insets.right, 0)
                binding.bottomBar.setPadding(insets.left, 0, insets.right, insets.bottom)
            }
        })

        // 底部播放栏控件
        ivCover = binding.bottomLayout.imageView
        tvTitle = binding.bottomLayout.textView
        tvWorkTitle = binding.bottomLayout.textView2
        ibStatus = binding.bottomLayout.button
        ibFloatLrcWindow = binding.bottomLayout.imageButton

        // 动画
        outAnim = AnimationUtils.loadAnimation(this, R.anim.move_bottom_out)
        inAnim = AnimationUtils.loadAnimation(this, R.anim.move_bottom_in)

        // 分割线
        loadingDecoration = LoadingFooterDecoration(binding.recyclerView)

        // 初始化布局管理器
        val storeLayoutType = viewModel.layoutType
        initLayout(storeLayoutType)

        // 收藏 tab 默认选“我的评价”
        binding.chipGroup.check(R.id.chipReview)

        // 免账号的实例(个人库)没有“我的评价/进度”，把它们收起来
        if (Api.isGuestHost(Api.currentHost())) {
            binding.bottomNav.menu.findItem(R.id.nav_favourites)?.isVisible = false
        }
    }

    // ==================== 监听器设置 ====================

    private fun setupListeners() {
        // 底栏：首页 / 收藏 / 我的
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTab(TAB_HOME)
                R.id.nav_favourites -> switchTab(TAB_FAVOURITES)
                R.id.nav_mine -> switchTab(TAB_MINE)
            }
            true
        }

        // 收藏 tab 的状态 chip
        chipTypeMap.forEach { (chipId, type) ->
            binding.chipGroup.findViewById<View>(chipId).setOnClickListener {
                applyFavouriteFilter(type)
            }
        }

        // 下拉刷新：首页“换一批”（随机页码，服务端的 seed 没用），收藏 tab 就是普通重拉
        binding.swipe.setOnRefreshListener {
            if (currentTab == TAB_HOME) {
                viewModel.shuffleStart()
            } else {
                viewModel.clearWorks()
            }
            viewModel.loadFromNetwork()
        }

        // 我的
        binding.mineAccount.setOnClickListener {
            App.getInstance().setValue(App.CONFIG_UPDATE_TIME, 0)
            startActivity(Intent(this, UserSwitchActivity::class.java))
        }
        binding.mineDownload.setOnClickListener {
            startActivity(Intent(this, DownLoadMissionActivity::class.java))
        }
        binding.mineHistory.setOnClickListener {
            startActivity(Intent(this, LastWatchActivity::class.java))
        }
        binding.mineSettings.setOnClickListener {
            startActivity(Intent(this, MoreActivity::class.java))
        }
        binding.mineCategory.setOnClickListener {
            showCategorySheet()
        }

        // 浮动歌词窗口按钮
        ibFloatLrcWindow.setOnClickListener {
            ctrlBinder?.let {
                if (it.isLrcWindowShow()) it.hideLrcFloatWindow() else it.showLrcFloatWindow()
            }
        }

        binding.btRetry.setOnClickListener {
            binding.swipe.visibility = View.VISIBLE
            binding.llNetErr.visibility = View.GONE
            viewModel.loadFromNetwork()
        }

        // 滚动加载更多
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (!recyclerView.canScrollVertically(1) && !(viewModel.loading.value ?: false)) {
                        val currentSize = viewModel.works.value?.size ?: 0
                        if (currentSize < viewModel.totalCount) {
                            viewModel.loadFromNetwork()
                        }
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                workAdapter?.updateAnimArgs(dx, dy)
            }
        })

        // 返回键处理
        onBackPressedDispatcher.addCallback(this, enabled = true) {
            // 保存状态
            viewModel.saveState()
            // 保存滚动位置
            val lm = binding.recyclerView.layoutManager
            val pos = when (lm) {
                is LinearLayoutManager -> lm.findFirstVisibleItemPosition()
                is GridLayoutManager -> lm.findFirstVisibleItemPosition()
                is StaggeredGridLayoutManager -> {
                    val positions = IntArray(lm.spanCount)
                    lm.findFirstVisibleItemPositions(positions)
                    positions.maxOrNull() ?: RecyclerView.NO_POSITION
                }
                else -> RecyclerView.NO_POSITION
            }
            if (pos != RecyclerView.NO_POSITION) {
                viewModel.setLastPosition(pos)
            }

            Toast.makeText(this@WorksActivity, "再次返回以退出", Toast.LENGTH_SHORT).show()
            isEnabled = false
            binding.root.postDelayed({ isEnabled = true }, 2000)
        }
    }

    // ==================== 观察 ViewModel ====================

    private fun observeViewModel() {
        // 作品列表变化
        viewModel.works.observe(this) { worksList ->
            if (currentTab != TAB_MINE) {
                binding.swipe.visibility = View.VISIBLE
                binding.llNetErr.visibility = View.GONE
            }
            if (workAdapter == null) {
                val layoutType = viewModel.layoutType
                setupAdapter(worksList, layoutType)
                binding.recyclerView.adapter = workAdapter
            }
            // 无论首次还是后续，统一调 submitList
            workAdapter?.submitList(worksList)
        }

        // 标题变化
        viewModel.title.observe(this) { title ->
            // “我的”页面的标题是固定的，别被列表回调覆盖
            if (currentTab != TAB_MINE) {
                supportActionBar?.title = title
            }
        }

        // 加载状态变化
        viewModel.loading.observe(this) { isLoading ->
            loadingDecoration?.isLoading = isLoading
            // 下拉的转圈由这次加载收尾
            if (!isLoading) binding.swipe.isRefreshing = false
        }

        // 错误事件
        viewModel.errorEvent.observe(this) { throwable ->
            if(throwable is Exception){
                alertException(throwable)
                if(viewModel.works.value?.size == 0 && currentTab != TAB_MINE){
                    binding.swipe.visibility = View.GONE
                    binding.llNetErr.visibility = View.VISIBLE
                    binding.tvNetErr.text = "${throwable.message}"
                }
            }
        }

        // 滚动位置恢复
        viewModel.scrollToPosition.observe(this) { position ->
            if (position != RecyclerView.NO_POSITION) {
                binding.recyclerView.layoutManager?.scrollToPosition(position)
            }
        }

        // 更改布局
        viewModel.layoutChangeLiveData.observe(this) { change ->
            val newLayout = viewModel.layoutType
            val worksList = viewModel.works.value ?:mutableListOf()
            setupAdapter(worksList, newLayout)
            initLayout(newLayout)
            binding.recyclerView.adapter = workAdapter
        }
    }

    private fun setupAdapter(worksList:MutableList<JSONObject>, layoutType:Int){
        workAdapter = WorkAdapter(worksList, layoutType).apply {
            setTagClickListener(this@WorksActivity)
            setVaClickListener(vaClickListener)
            setCirclesClickListener(circlesClickListener)
            setItemClickListener { v ->
                val item = v.tag as JSONObject
                val intent = Intent(v.context, WorkTreeActivity::class.java).apply {
                    putExtra("work_json_str", item.toString())
                }
                val heroView = v.findViewById<View>(R.id.ivCover)
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this@WorksActivity, heroView, "hero_image"
                )
                startActivity(intent, options.toBundle())
            }
            setItemLongClickListener { v ->
                if (viewModel.type != MainViewModel.TYPE_LOCAL_WORK) return@setItemLongClickListener true
                showDeleteCachePopup(v)
                true
            }
        }
    }

    // ==================== 布局初始化 ====================

    private fun initLayout(layoutType: Int) {
        binding.recyclerView.removeItemDecoration(loadingDecoration!!)
        binding.recyclerView.addItemDecoration(loadingDecoration!!)
        val col: Int
        val layoutManager: RecyclerView.LayoutManager?

        when (layoutType) {
            WorkAdapter.LAYOUT_LIST -> {
                layoutManager = LinearLayoutManager(this)
                col = 1
            }
            WorkAdapter.LAYOUT_SMALL_GRID -> {
                col = max(resources.displayMetrics.widthPixels / 395, 3)
                layoutManager = GridLayoutManager(this, col).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (position == (viewModel.works.value?.size ?: 0)) col else 1
                        }
                    }
                }
            }
            WorkAdapter.LAYOUT_BIG_GRID -> {
                col = max(resources.displayMetrics.widthPixels / 395, 2)
                layoutManager = GridLayoutManager(this, col).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (position == (viewModel.works.value?.size ?: 0)) col else 1
                        }
                    }
                }
            }
            WorkAdapter.LAYOUT_STAGGERED -> {
                col = max(resources.displayMetrics.widthPixels / 395, 2)
                layoutManager = StaggeredGridLayoutManager(col, StaggeredGridLayoutManager.VERTICAL)
            }
            else -> {
                layoutManager = LinearLayoutManager(this)
                col = 1
            }
        }

        binding.recyclerView.layoutManager = layoutManager
        // adapter 已经在 observe 中设置，此处不需要重复设置
    }

    // ==================== 底栏 / 收藏 chip ====================

    private fun switchTab(tab: Int) {
        // 已经在首页时再点一次首页 = 清掉标签/声优/社团筛选，回到全部作品
        if (tab == TAB_HOME && currentTab == TAB_HOME) {
            showAllWorks()
            return
        }
        currentTab = tab
        when (tab) {
            TAB_HOME -> {
                binding.chipBar.visibility = View.GONE
                binding.mineScroll.visibility = View.GONE
                showAllWorks()
            }
            TAB_FAVOURITES -> {
                binding.chipBar.visibility = View.VISIBLE
                binding.mineScroll.visibility = View.GONE
                applyFavouriteFilter(favType)
            }
            TAB_MINE -> {
                binding.chipBar.visibility = View.GONE
                binding.mineScroll.visibility = View.VISIBLE
                binding.swipe.visibility = View.GONE
                binding.llNetErr.visibility = View.GONE
                binding.tvAccount.text = getString(R.string.account) +
                        "  ·  " + (App.getInstance().currentUser()?.getName() ?: "")
                supportActionBar?.title = getString(R.string.mine)
            }
        }
    }

    /** 首页 = 全部作品，且不允许带着上次的筛选状态进来 */
    private fun showAllWorks() {
        binding.swipe.visibility = View.VISIBLE
        binding.llNetErr.visibility = View.GONE
        viewModel.resetFilter()
        viewModel.clearWorks()
        viewModel.loadFromNetwork()
    }

    /** 收藏 tab 里的一个状态 */
    private fun applyFavouriteFilter(type: Int) {
        favType = type
        binding.swipe.visibility = View.VISIBLE
        binding.llNetErr.visibility = View.GONE
        viewModel.clearWorks()
        viewModel.type = type
        viewModel.loadFromNetwork()
    }

    /** 声优 / 标签 / 社团 */
    private fun showCategorySheet() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_category, null)
        content.findViewById<View>(R.id.categoryVa).setOnClickListener {
            sheet.dismiss()
            startActivityForResult(Intent(this, VasActivity::class.java), VA_SELECT_RESULT)
        }
        content.findViewById<View>(R.id.categoryTag).setOnClickListener {
            sheet.dismiss()
            startActivityForResult(Intent(this, TagsActivity::class.java), TAG_SELECT_RESULT)
        }
        content.findViewById<View>(R.id.categoryCircles).setOnClickListener {
            sheet.dismiss()
            startActivityForResult(Intent(this, CirclesActivity::class.java), CIRCLES_SELECT_RESULT)
        }
        sheet.setContentView(content)
        sheet.show()
    }

    // ==================== 底部播放栏控制 ====================

    private fun toggleBottom() {
        if (shouldShowAnim && binding.bottomLayout.root.visibility == View.VISIBLE) {
            shouldShowAnim = false
            binding.bottomLayout.root.startAnimation(outAnim)
            binding.bottomLayout.root.postDelayed({
                binding.bottomLayout.root.visibility = View.GONE
                shouldShowAnim = true
            }, outAnim?.duration ?: 300)
        } else if (shouldShowAnim && binding.bottomLayout.root.visibility == View.GONE) {
            shouldShowAnim = false
            binding.bottomLayout.root.visibility = View.VISIBLE
            binding.bottomLayout.root.startAnimation(inAnim)
            binding.bottomLayout.root.postDelayed({
                shouldShowAnim = true
            }, inAnim?.duration ?: 300)
        }
    }

    // ==================== 长按删除缓存弹窗 ====================

    private fun showDeleteCachePopup(v: View) {
        val popup = ListPopupWindow(v.context).apply {
            isModal = true
            anchorView = v
            setAdapter(ArrayAdapter(
                v.context,
                android.R.layout.simple_list_item_1,
                listOf(getString(R.string.delete_cache))
            ))
            setOnItemClickListener { _, _, _, _ ->
                val item = v.tag as JSONObject
                try {
                    LocalFileCache.getInstance().removeWork(item.getInt("id"))
                } catch (e: JSONException) {
                    e.printStackTrace()
                    alertException(e)
                }
                val index = viewModel.works.value?.indexOf(item) ?: -1
                if (index != -1) {
                    viewModel.works.value?.removeAt(index)
                    workAdapter?.notifyItemRemoved(index)
                }
                dismiss()
            }
        }
        popup.show()
    }

    // ==================== 菜单 ====================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val layoutMenu = menu.addSubMenu(0, 9, 9, R.string.works_layout).apply {
            setIcon(R.drawable.ic_baseline_view_column_24)
            add(2, 10, 10, R.string.list_layout)
            add(2, 11, 11, R.string.cover_layout)
            add(2, 12, 12, R.string.detail_layout)
            add(2, 13, 13, R.string.staggered)
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }

        val sortMenu = menu.addSubMenu(0, 16, 16, R.string.sort).apply {
            setIcon(R.drawable.ic_baseline_sort_24)
            // 当前排序维度后面拼上箭头，免得看不出现在是按什么排、哪种方向
            add(3, 17, 17, sortItemTitle(R.string.release_date, "release"))
            add(3, 18, 18, sortItemTitle(R.string.rj_number, "id"))
            add(3, 19, 19, sortItemTitle(R.string.prize, "price"))
            add(3, 20, 20, sortItemTitle(R.string.last_in_lib, "create_date"))
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }

        // 搜索留在最右
        menu.add(0, 23, 23, R.string.search).apply {
            setIcon(R.drawable.ic_baseline_search_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.groupId) {
            2 -> { // 布局切换
                val layoutType = when (item.itemId) {
                    10 -> WorkAdapter.LAYOUT_LIST
                    11 -> WorkAdapter.LAYOUT_SMALL_GRID
                    12 -> WorkAdapter.LAYOUT_BIG_GRID
                    13 -> WorkAdapter.LAYOUT_STAGGERED
                    else -> WorkAdapter.LAYOUT_STAGGERED
                }
                viewModel.changeLayoutType(layoutType)
                return true
            }
            3 -> { // 排序切换
                var needUpdate = false
                when (item.itemId) {
                    17 -> { setOrder("release"); needUpdate = true }
                    18 -> { setOrder("id"); needUpdate = true }
                    19 -> { setOrder("price"); needUpdate = true }
                    20 -> { setOrder("create_date"); needUpdate = true }
                }
                if (needUpdate) {
                    invalidateOptionsMenu()   // 让排序标题上的箭头跟着变
                    viewModel.clearWorks()
                    viewModel.loadFromNetwork()
                }
                return true
            }
        }

        return when (item.itemId) {
            23 -> {
                startActivity(Intent(this, SearchActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ==================== Activity 结果 ====================

    /** 排序菜单项标题：当前那一项后面带方向箭头 */
    private fun sortItemTitle(titleRes: Int, order: String): String {
        val title = getString(titleRes)
        if (Api.currentOrder() != order) return title
        return title + if (Api.isAscending()) "  ↑" else "  ↓"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TAG_SELECT_RESULT || requestCode == VA_SELECT_RESULT) {
            if (resultCode == RESULT_OK && data != null) {
                val resultType = data.getStringExtra("resultType")
                if (resultType == null) return
                when (resultType) {
                    "va" -> {
                        val vaId = data.getStringExtra("id") ?: return
                        if (vaId != viewModel.vaId) {
                            viewModel.vaName = data.getStringExtra("name") ?: ""
                            viewModel.clearWorks()
                            viewModel.vaId = vaId
                        }
                        viewModel.type = MainViewModel.TYPE_VA_WORK
                        viewModel.loadFromNetwork()
                    }
                    "tag" -> {
                        val tagId = data.getIntExtra("id", -1)
                        if (tagId != viewModel.tagId) {
                            viewModel.tagStr = data.getStringExtra("name") ?: ""
                            viewModel.clearWorks()
                            viewModel.tagId = tagId
                        }
                        viewModel.type = MainViewModel.TYPE_TAG_WORK
                        viewModel.loadFromNetwork()
                    }
                }
            }
        } else if (requestCode == CIRCLES_SELECT_RESULT) {
            if (resultCode == RESULT_OK && data != null) {
                val resultType = data.getStringExtra("resultType")
                if (resultType == "circles") {
                    val circlesId = data.getLongExtra("id", -1)
                    if (circlesId != viewModel.circlesId && circlesId != -1L) {
                        viewModel.circlesName = data.getStringExtra("name") ?: ""
                        viewModel.clearWorks()
                        viewModel.circlesId = circlesId
                        viewModel.type = MainViewModel.TYPE_CIRCLES_WORK
                        viewModel.loadFromNetwork()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val resultType = intent.getStringExtra("resultType")
        when (resultType) {
            "va" -> {
                val vaId = intent.getStringExtra("id") ?: return
                if (vaId != viewModel.vaId || viewModel.type != MainViewModel.TYPE_VA_WORK) {
                    viewModel.vaName = intent.getStringExtra("name") ?: ""
                    viewModel.clearWorks()
                    viewModel.vaId = vaId
                }
                viewModel.type = MainViewModel.TYPE_VA_WORK
                viewModel.loadFromNetwork()
            }
            "tag" -> {
                val tagId = intent.getIntExtra("id", -1)
                if (tagId != viewModel.tagId || viewModel.type != MainViewModel.TYPE_TAG_WORK) {
                    viewModel.tagStr = intent.getStringExtra("name") ?: ""
                    viewModel.clearWorks()
                    viewModel.tagId = tagId
                }
                viewModel.type = MainViewModel.TYPE_TAG_WORK
                viewModel.loadFromNetwork()
            }
            else -> {
                viewModel.clearWorks()
                viewModel.type = MainViewModel.TYPE_ALL_WORK
                viewModel.loadFromNetwork()
            }
        }
    }

    // ==================== Service 连接 ====================

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        ctrlBinder = service as AudioService.CtrlBinder
        ibStatus.setOnClickListener {
            val controller = ctrlBinder?.controller ?: return@setOnClickListener
            val state = controller.playbackState?.state
            if (state == null) {
                controller.transportControls.play()
            } else if (state == PlaybackStateCompat.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }

        binding.bottomLayout.root.setOnClickListener { v ->
            try {
                if (ctrlBinder?.currentTitle?.endsWith("mp4") == true) {
                    startActivity(Intent(this, VideoPlayerActivity::class.java))
                } else {
                    val intent = Intent(this, AudioPlayerActivity::class.java)
                    val heroView = v.findViewById<View>(R.id.imageView)
                    val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        this, heroView, "hero_bottom"
                    )
                    startActivity(intent, options.toBundle())
                }
            } catch (e: JSONException) {
                e.printStackTrace()
                alertException(e)
            }
        }

        ctrlBinder?.addMusicChangeListener(this)
    }

    override fun onServiceDisconnected(name: ComponentName?) {}

    // ==================== MusicChangeListener 实现 ====================

    override fun onAlbumChange(rjNumber: Long) {
        if (rjNumber != 0L && binding.bottomLayout.root.visibility == View.GONE) {
            toggleBottom()
        }
        Glide.with(this).load(Api.minCoverImageUrl(rjNumber)).apply(App.getInstance().radius5Pic).into(ivCover)
    }

    override fun onAudioChange(audio: JSONObject) {
        try {
            tvTitle.text = audio.getString("title")
            tvWorkTitle.text = audio.getString("workTitle")
        } catch (e: JSONException) {
            e.printStackTrace()
            alertException(e)
        }
    }

    override fun onStatusChange(status: Int) {
        ibStatus.setImageResource(
            if (status == 0) R.drawable.ic_baseline_play_arrow_white_24
            else R.drawable.ic_baseline_pause_white_24
        )
    }

    // ==================== TagClickListener 实现 ====================

    override fun onTagClick(jsonObject: JSONObject?) {
        jsonObject?.let {
            try {
                val tagId = it.getInt("id")
                if (tagId != viewModel.tagId || viewModel.type != MainViewModel.TYPE_TAG_WORK) {
                    viewModel.tagStr = it.getString("name")
                    viewModel.clearWorks()
                    viewModel.tagId = tagId
                }
                viewModel.type = MainViewModel.TYPE_TAG_WORK
                viewModel.loadFromNetwork()
            } catch (e: JSONException) {
                e.printStackTrace()
                alertException(e)
            }
        }
    }

    private val vaClickListener = TagsView.TagClickListener<JSONObject?> { jsonObject ->
        jsonObject?.let {
            try {
                val vaId = it.getString("id")
                if (vaId != viewModel.vaId || viewModel.type != MainViewModel.TYPE_VA_WORK) {
                    viewModel.vaName = it.getString("name")
                    viewModel.clearWorks()
                    viewModel.vaId = vaId
                }
                viewModel.type = MainViewModel.TYPE_VA_WORK
                viewModel.loadFromNetwork()
            } catch (e: JSONException) {
                e.printStackTrace()
                alertException(e)
            }
        }
    }

    private val circlesClickListener = TagsView.TagClickListener<String?> { circlesName ->
        circlesName?.let {
            val circlesId = App.getInstance().mapCirclesId(it)
            if (circlesId != -1L) {
                viewModel.clearWorks()
                viewModel.circlesName = it
                viewModel.circlesId = circlesId
                viewModel.type = MainViewModel.TYPE_CIRCLES_WORK
                viewModel.loadFromNetwork()
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onStart() {
        super.onStart()
        // 从后台回到前台 = 重新进 App：首页回到“全部作品”，不要停在上次点的标签/声优筛选上
        // （“我的/收藏” tab 不重置；本来就是全部作品也不打扰，保留滚动位置）
        if (App.getInstance().consumeHomeNeedsReset() && currentTab == TAB_HOME &&
            viewModel.type != MainViewModel.TYPE_ALL_WORK
        ) {
            showAllWorks()
        }
    }

    override fun onDestroy() {
        ctrlBinder?.removeMusicChangeListener(this)
        val state = ctrlBinder?.controller?.playbackState?.state
        if (state == null || state == PlaybackStateCompat.STATE_STOPPED || state == PlaybackStateCompat.STATE_PAUSED) {
            stopService(Intent(this, AudioService::class.java))
        }
        unbindService(this)
        super.onDestroy()
        loadingDecoration?.destroy()
    }

    companion object {
        private const val TAG = "WorksActivity"
        const val TAG_SELECT_RESULT = 14
        const val VA_SELECT_RESULT = 15
        const val CIRCLES_SELECT_RESULT = 16

        private const val TAB_HOME = 0
        private const val TAB_FAVOURITES = 1
        private const val TAB_MINE = 2
    }
}