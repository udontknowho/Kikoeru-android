package com.zinhao.kikoeru;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * 验证遮罩页:整屏不透明地把 App 内容盖住,再把系统验证弹出来。
 *
 * 之所以单独做一个 Activity,而不是把验证弹窗直接挂在当前页面上:
 * 弹窗只遮住自己那一小块,后面的列表照样看得见。盖一层全屏页面才真的不露。
 */
public class LockActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        findViewById(R.id.unlockButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLock.authenticate(LockActivity.this);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 进来就弹,不用先点按钮
        AppLock.authenticate(this);
    }

    /** 返回键不许把它关掉,只把整个任务丢到后台 */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
