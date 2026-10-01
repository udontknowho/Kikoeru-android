package com.zinhao.kikoeru;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import com.zinhao.kikoeru.databinding.ActivityMoreBinding;
import com.zinhao.kikoeru.network.HttpClientManager;
import org.jetbrains.annotations.NotNull;

public class MoreActivity extends BaseActivity implements CompoundButton.OnCheckedChangeListener {
    private View itemOnlyLoadLrc;
    private CheckBox cbOnlyLrcWork;

    private View vLicense;

    private View itemSaveExternal;
    private CheckBox cbSaveExternal;

    private View vAbout;

    private View itemDebug;
    private CheckBox cbDebug;
    private View itemAppLock;
    private CheckBox cbAppLock;
    private View itemProxy;
    private CheckBox cbProxy;
    private View itemProxyAddr;
    private TextView tvProxyAddr;
    private View itemLrcSize;
    private TextView tvLrcSize;
    private View itemLrcColor;
    private TextView tvLrcColor;
    private View itemLrcAlpha;
    private TextView tvLrcAlpha;
    private View itemLrcBg;
    private TextView tvLrcBg;
    private ActivityMoreBinding viewBinding;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = ActivityMoreBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());
        setSupportActionBar(viewBinding.toolbar);
        setSafeArea(viewBinding.appBarLayout, new InsetReady() {
            @Override
            public void onInsetReady(@NotNull Insets insets) {
                viewBinding.content.setPadding(insets.left, 0, insets.right, insets.bottom);
            }
        });
        itemOnlyLoadLrc = viewBinding.relativeLayout;
        itemOnlyLoadLrc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cbOnlyLrcWork.toggle();
            }
        });
        cbOnlyLrcWork = viewBinding.checkBox;
        long onlyLrcFlag = App.getInstance().getValue(App.CONFIG_ONLY_DISPLAY_LRC, 1);
        cbOnlyLrcWork.setChecked(onlyLrcFlag == 1);
        cbOnlyLrcWork.setOnCheckedChangeListener(this);

        itemSaveExternal = findViewById(R.id.saveExternal);
        itemSaveExternal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cbSaveExternal.toggle();
            }
        });
        cbSaveExternal = findViewById(R.id.checkBox3);
        cbSaveExternal.setChecked(App.getInstance().isSaveExternal());
        cbSaveExternal.setOnCheckedChangeListener(this);

        vLicense = findViewById(R.id.license);
        vLicense.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MoreActivity.this, LicenseActivity.class));
            }
        });

        vAbout = findViewById(R.id.about);
        vAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MoreActivity.this, AboutActivity.class));
            }
        });

        itemDebug = viewBinding.debug;
        itemDebug.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cbDebug.toggle();
            }
        });
        cbDebug = findViewById(R.id.checkBox1);
        cbDebug.setChecked(App.getInstance().isAppDebug());
        cbDebug.setOnCheckedChangeListener(this);

        itemAppLock = viewBinding.appLock;
        itemAppLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cbAppLock.toggle();
            }
        });
        cbAppLock = viewBinding.cbAppLock;
        cbAppLock.setChecked(AppLock.isEnabled());
        cbAppLock.setOnCheckedChangeListener(this);

        itemProxy = viewBinding.proxySwitch;
        itemProxy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cbProxy.toggle();
            }
        });
        cbProxy = viewBinding.cbProxy;
        cbProxy.setChecked(App.getInstance().getValue(App.CONFIG_PROXY_ENABLED, 1L) == 1L);
        cbProxy.setOnCheckedChangeListener(this);

        itemProxyAddr = viewBinding.proxyAddr;
        tvProxyAddr = viewBinding.tvProxyAddr;
        tvProxyAddr.setText(App.getInstance().getValue(App.CONFIG_PROXY_ADDR, App.DEFAULT_PROXY_ADDR));
        itemProxyAddr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProxyAddrDialog();
            }
        });

        itemLrcSize = viewBinding.lrcSize;
        tvLrcSize = viewBinding.tvLrcSize;
        tvLrcSize.setText(String.format(java.util.Locale.US, "%dsp", App.getInstance().getLrcTextSize()));
        itemLrcSize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLrcSizeDialog();
            }
        });

        itemLrcColor = viewBinding.lrcColor;
        tvLrcColor = viewBinding.tvLrcColor;
        tvLrcColor.setText(String.format(java.util.Locale.US, "#%06X", App.getInstance().getLrcTextColor()));
        tvLrcColor.setTextColor(0xFF000000 | App.getInstance().getLrcTextColor());
        itemLrcColor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLrcColorDialog();
            }
        });

        itemLrcAlpha = viewBinding.lrcAlpha;
        tvLrcAlpha = viewBinding.tvLrcAlpha;
        tvLrcAlpha.setText(App.getInstance().getLrcTextAlpha() * 100 / 255 + "%");
        itemLrcAlpha.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLrcAlphaDialog();
            }
        });

        itemLrcBg = viewBinding.lrcBg;
        tvLrcBg = viewBinding.tvLrcBg;
        tvLrcBg.setText(bgLabel(App.getInstance().getLrcBgColor(), App.getInstance().getLrcBgAlpha()));
        itemLrcBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLrcBgDialog();
            }
        });


        viewBinding.rlHomeTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewBinding.cbHomeTab.toggle();
            }
        });
        viewBinding.cbHomeTab.setChecked(App.getInstance().isUseNewLayout());
        viewBinding.cbHomeTab.setOnCheckedChangeListener(this);
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        if (compoundButton == cbOnlyLrcWork) {
            long value = b ? 1 : 0;
            App.getInstance().setValue(App.CONFIG_ONLY_DISPLAY_LRC, value);
            Api.setSubtitle((int) value);
        }

        if (compoundButton == cbDebug) {
            App.getInstance().setAppDebug(b);
        }

        if (compoundButton == cbAppLock) {
            // 没指纹也没锁屏密码就不让开,不然是自己把自己锁在外面
            if (b && !AppLock.isAvailable()) {
                Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show();
                cbAppLock.toggle();
                return;
            }
            AppLock.setEnabled(b);
        }

        if (compoundButton == cbProxy) {
            App.getInstance().setValue(App.CONFIG_PROXY_ENABLED, b ? 1L : 0L);
            // 探测结果有缓存,改完设置得手动作废一次
            HttpClientManager.INSTANCE.resetProxyCache();
        }

        if( compoundButton == viewBinding.cbHomeTab ){
            App.getInstance().setUseNewLayout(b);
            startActivity(new Intent(this, LauncherActivity.class));
            finish();
            return;
        }

        if (cbSaveExternal == compoundButton) {
            if (b) {
                boolean result = requestReadWriteExternalPermission(new Runnable() {
                    @Override
                    public void run() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (!Environment.isExternalStorageManager()) {
                                if (cbSaveExternal.isChecked()) {
                                    cbSaveExternal.toggle();
                                }
                                return;
                            }
                        } else {
                            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                                if (cbSaveExternal.isChecked()) {
                                    cbSaveExternal.toggle();
                                }
                                return;
                            }
                        }
                        App.getInstance().setSaveExternal(true);
                        if (!cbSaveExternal.isChecked()) {
                            cbSaveExternal.toggle();
                        }
                    }
                });
                if (result) {
                    App.getInstance().setSaveExternal(true);
                }
            } else {
                App.getInstance().setSaveExternal(false);
            }
        }
    }

    /** 代理地址:主机:端口 */
    private void showProxyAddrDialog() {
        final EditText input = new EditText(this);
        input.setText(App.getInstance().getValue(App.CONFIG_PROXY_ADDR, App.DEFAULT_PROXY_ADDR));
        input.setSelection(input.getText().length());
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.RoundedAlertDialog);
        builder.setTitle(R.string.proxy_addr);
        builder.setView(input);
        builder.setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                String addr = input.getText().toString().trim();
                if (!isValidProxyAddr(addr)) {
                    Toast.makeText(MoreActivity.this, R.string.proxy_addr_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                App.getInstance().setValue(App.CONFIG_PROXY_ADDR, addr);
                tvProxyAddr.setText(addr);
                HttpClientManager.INSTANCE.resetProxyCache();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private static boolean isValidProxyAddr(String addr) {
        int sep = addr.lastIndexOf(':');
        if (sep <= 0 || sep == addr.length() - 1) {
            return false;
        }
        try {
            int port = Integer.parseInt(addr.substring(sep + 1).trim());
            return port > 0 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 桌面字幕字号(自定义,8~120) */
    private void showLrcSizeDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(App.getInstance().getLrcTextSize()));
        input.setSelection(input.getText().length());
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.RoundedAlertDialog);
        builder.setTitle(R.string.lrc_text_size);
        builder.setView(input);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int size;
                try {
                    size = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException e) {
                    size = -1;
                }
                if (size < 8 || size > 120) {
                    Toast.makeText(MoreActivity.this, R.string.lrc_size_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                App.getInstance().setValue(App.CONFIG_LRC_TEXT_SIZE, (long) size);
                tvLrcSize.setText(size + "sp");
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    /** 桌面字幕颜色 */
    private void showLrcColorDialog() {
        final int[] colors = {0xFFFFFF, 0x000000, 0xFFD54F, 0x8EE8E8, 0xFFB6C1, 0x6FCF97, 0xFF8C42, 0xB693E0};
        final String[] items = new String[colors.length];
        int checked = 0;
        int current = App.getInstance().getLrcTextColor();
        for (int i = 0; i < colors.length; i++) {
            items[i] = String.format(java.util.Locale.US, "#%06X", colors[i]);
            if (colors[i] == current) {
                checked = i;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.RoundedAlertDialog);
        builder.setTitle(R.string.lrc_text_color);
        builder.setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                App.getInstance().setValue(App.CONFIG_LRC_TEXT_COLOR, (long) colors[which]);
                tvLrcColor.setText(items[which]);
                tvLrcColor.setTextColor(0xFF000000 | colors[which]);
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /** 桌面字幕透明度 */
    private void showLrcAlphaDialog() {
        final int[] percents = {100, 80, 60, 40, 20};
        final String[] items = new String[percents.length];
        int checked = 0;
        int current = App.getInstance().getLrcTextAlpha();
        for (int i = 0; i < percents.length; i++) {
            items[i] = percents[i] + "%";
            if (percents[i] * 255 / 100 == current) {
                checked = i;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.RoundedAlertDialog);
        builder.setTitle(R.string.lrc_text_alpha);
        builder.setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                App.getInstance().setValue(App.CONFIG_LRC_TEXT_ALPHA, (long) (percents[which] * 255 / 100));
                tvLrcAlpha.setText(items[which]);
                dialog.dismiss();
            }
        });
        builder.show();
    }

    /** 桌面字幕背景:颜色和它的透明度合成一项 */
    private void showLrcBgDialog() {
        final int[] rgbs = {0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0xFFFFFF};
        final int[] alphas = {0, 51, 102, 153, 204, 102};
        final String[] items = new String[rgbs.length];
        int checked = 0;
        int currentRgb = App.getInstance().getLrcBgColor();
        int currentAlpha = App.getInstance().getLrcBgAlpha();
        for (int i = 0; i < rgbs.length; i++) {
            items[i] = i == 0 ? getString(R.string.lrc_bg_none) : bgLabel(rgbs[i], alphas[i]);
            boolean sameColor = rgbs[i] == currentRgb;
            if (i == 0 ? currentAlpha <= 0 : sameColor && Math.abs(alphas[i] - currentAlpha) < 8) {
                checked = i;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.RoundedAlertDialog);
        builder.setTitle(R.string.lrc_text_bg);
        builder.setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                App.getInstance().setValue(App.CONFIG_LRC_BG_COLOR, (long) rgbs[which]);
                App.getInstance().setValue(App.CONFIG_LRC_BG_ALPHA, (long) alphas[which]);
                tvLrcBg.setText(items[which]);
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private String bgLabel(int rgb, int alpha) {
        if (alpha <= 0) {
            return getString(R.string.lrc_bg_none);
        }
        return String.format(java.util.Locale.US, "#%06X · %d%%", rgb, alpha * 100 / 255);
    }
}