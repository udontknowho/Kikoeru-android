package com.zinhao.kikoeru

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Filter
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.zinhao.kikoeru.databinding.ActivityLoginAccountBinding
import com.zinhao.kikoeru.viewmodel.LoginViewModel

class LoginAccountActivity : BaseActivity() {
    private var tilUser: TextInputLayout? = null
    private var tilPassword: TextInputLayout? = null
    private var tilServer: TextInputLayout? = null
    private var btSignIn: Button? = null
    private var btGuest: Button? = null
    private var btSignUp: Button? = null

    private var viewModel: LoginViewModel? = null
    private lateinit var viewBinding: ActivityLoginAccountBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityLoginAccountBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        viewModel = ViewModelProvider(this).get<LoginViewModel>(LoginViewModel::class.java)
        initViews()
        observeViewModel()
        setupListeners()
        initEdit()
    }

    private fun setupListeners() {
        btSignIn!!.setOnClickListener(View.OnClickListener { v: View? ->
            // 更新 ViewModel 中的值
            updateViewModelInputs()
            if (Api.hostKind(currentHostText()) == Api.HostKind.NO_TOKEN) {
                // 免账号站点：直接建本地 guest 用户，不走登录接口
                viewModel!!.loginWithoutAccount()
            } else {
                // 游客 token 的站点，字段里已经填好 guest/guest，走正常登录
                viewModel!!.login()
            }
        })

        btGuest!!.setOnClickListener(View.OnClickListener { v: View? ->
            updateViewModelInputs()
            if (Api.hostKind(currentHostText()) == Api.HostKind.NO_TOKEN) {
                viewModel!!.loginWithoutAccount()
            } else {
                viewModel!!.loginAsGuest()
            }
        })
    }

    private fun currentHostText(): String =
        tilServer?.editText?.text?.toString()?.trim() ?: ""

    /** 免账号/游客站点：账号密码框禁用，登录按钮换成对应文案 */
    private fun applyHostMode(host: String) {
        when (Api.hostKind(host)) {
            Api.HostKind.NO_TOKEN -> {
                tilUser?.isEnabled = false
                tilPassword?.isEnabled = false
                btSignIn?.text = getString(R.string.user_enter)
            }

            Api.HostKind.GUEST_TOKEN -> {
                // 这种库只认游客账号，直接把 guest/guest 填好免得输错
                tilUser?.isEnabled = false
                tilPassword?.isEnabled = false
                tilUser?.editText?.setText("guest")
                tilPassword?.editText?.setText("guest")
                btSignIn?.text = getString(R.string.user_enter_guest)
            }

            else -> {
                tilUser?.isEnabled = true
                tilPassword?.isEnabled = true
                btSignIn?.text = getString(R.string.user_sign_in)
            }
        }
    }

    private fun observeViewModel() {
        viewModel!!.getIsLoading().observe(this, object : Observer<Boolean?> {
            override fun onChanged(isLoading: Boolean?) {
                isLoading?.let {
                    btSignIn!!.setEnabled(!it)
                    btGuest!!.setEnabled(!it)
                }

            }
        })
        // 观察错误消息
        viewModel!!.getErrorMessage().observe(this, Observer { error: String? ->
            if (error != null && !error.isEmpty()) {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        })

        // 观察登录成功
        viewModel!!.getLoginSuccess().observe(this, Observer { success: Boolean? ->
            success?.let {
                if (it) {
                    navigateToMain()
                }
            }

        })
    }

    private fun initEdit() {
        val etUser = tilUser!!.getEditText()
        val etPassword = tilPassword!!.getEditText()
        val etServer = tilServer!!.getEditText()

        if (etUser == null || etPassword == null || etServer == null) return

        // 服务器下拉：内置站点 + “自定义…”；想用别的地址就选自定义自己填
        if (etServer is MaterialAutoCompleteTextView) {
            val entries = Api.BUILTIN_HOSTS.toMutableList().apply { add(getString(R.string.host_custom)) }
            // 默认的过滤会拿当前文本去筛，字段里已经有完整地址时列表会什么都显不出来，
            // 所以换成一个“永远全显示”的 adapter，并在点击/获得焦点时主动展开
            etServer.setAdapter(object : ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, entries
            ) {
                override fun getFilter(): Filter = object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults =
                        FilterResults().apply {
                            values = entries
                            count = entries.size
                        }

                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        notifyDataSetChanged()
                    }
                }
            })
            etServer.threshold = 0
            etServer.setOnClickListener { etServer.showDropDown() }
            etServer.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) etServer.showDropDown()
            }
            etServer.setOnItemClickListener { _, _, position, _ ->
                if (position == Api.BUILTIN_HOSTS.size) {
                    // 选中“自定义…”：清空让用户自己填，账号密码框照常可用
                    etServer.setText("")
                    etServer.requestFocus()
                }
            }
        }
        etServer.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                applyHostMode(s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 设置默认值
        val currentUser = App.getInstance().currentUser()
        if (currentUser != null) {
            etUser.setText(currentUser.getName())
            etPassword.setText(currentUser.getPassword())
            etServer.setText(currentUser.getHost())
        } else {
            etUser.setText("guest")
            etPassword.setText("guest")
            etServer.setText(Api.REMOTE_HOST)
        }
        applyHostMode(etServer.getText().toString())
    }

    private fun updateViewModelInputs() {
        val etUser = tilUser!!.getEditText()
        val etPassword = tilPassword!!.getEditText()
        val etServer = tilServer!!.getEditText()

        if (etUser != null) {
            viewModel!!.setUsername(etUser.getText().toString().trim { it <= ' ' })
        }
        if (etPassword != null) {
            viewModel!!.setPassword(etPassword.getText().toString().trim { it <= ' ' })
        }
        if (etServer != null) {
            // 顺手把粘进来的网页路径(/works 之类)削掉
            viewModel!!.setHost(Api.normalizeHost(etServer.getText().toString()))
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this@LoginAccountActivity, WorksActivity::class.java))
        finish()
    }

    private fun initViews() {
        tilUser = viewBinding.textInputLayout
        tilPassword = viewBinding.textInputLayout2
        tilServer = viewBinding.textInputLayout3
        btSignIn = viewBinding.button2
        btGuest = viewBinding.button4
        btSignUp = viewBinding.button3
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val menuItem2 = menu.add(0, 2, 2, "about")
        val menuItem3 = menu.add(0, 3, 3, "choose user")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.getItemId() == 2) {
            startActivity(Intent(this, AboutActivity::class.java))
        } else if (item.getItemId() == 3) {
            startActivity(Intent(this, UserSwitchActivity::class.java))
        }

        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val TAG = "LoginAccountActivity"
    }
}