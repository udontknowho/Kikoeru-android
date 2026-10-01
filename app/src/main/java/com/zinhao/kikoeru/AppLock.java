package com.zinhao.kikoeru;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.widget.Toast;

/**
 * 回到 App 时的指纹/锁屏密码验证。
 *
 * 状态只有两个静态 flag:锁的是"别人拿起你手机翻你的库",不是防调试、防逆向,
 * 所以不落盘、不做超时设置 —— 退到后台就重新上锁。
 */
public final class AppLock {
    public static final String CONFIG_APP_LOCK = "app_lock";

    private static boolean unlocked = false;
    private static boolean prompting = false;
    /** 只是为了防止 CancellationSignal 被回收 */
    private static CancellationSignal signal;

    private AppLock() {
    }

    public static boolean isEnabled() {
        return App.getInstance().getValue(CONFIG_APP_LOCK, 0L) == 1L;
    }

    public static void setEnabled(boolean enabled) {
        App.getInstance().setValue(CONFIG_APP_LOCK, enabled ? 1L : 0L);
        unlocked = !enabled;
    }

    /** 设备连指纹/锁屏密码都没有的时候,这个开关没有意义 */
    public static boolean isAvailable() {
        BiometricManager manager = BiometricManager.from(App.getInstance());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS;
        }
        // Android 10 上没有 Authenticators 这套常量
        return manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    /** App 退到后台:重新上锁,并清掉弹窗状态让下次进来还能问 */
    public static void onBackground() {
        unlocked = false;
        prompting = false;
        signal = null;
    }

    public static void authenticate(Activity activity) {
        // 这两个 Activity 是"过路"的:LauncherActivity 起来就 finish 转下一个,
        // LrcFloatWindow 绑完服务立刻 finishAndRemoveTask。系统弹窗挂在它们身上,
        // 宿主一死弹窗就被取消,会被当成"验证失败"把 App 送进后台。
        if (prompting || unlocked || activity.isFinishing()
                || activity instanceof LauncherActivity || activity instanceof LrcFloatWindow) {
            return;
        }
        prompting = true;
        signal = new CancellationSignal();
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.app_lock_title))
                .setSubtitle(activity.getString(R.string.app_lock_subtitle));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            builder.setDeviceCredentialAllowed(true);
        }
        builder.build().authenticate(signal, activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        unlocked = true;
                        prompting = false;
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        // 指纹和锁屏密码都没了(验证过程中被删掉也一样)。宁可放进去,
                        // 也不把用户锁在自己的 App 外面。
                        if (!isAvailable()) {
                            unlocked = true;
                            prompting = false;
                            Toast.makeText(activity, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show();
                            return;
                        }
                        // 其余全部按"没过"处理:退到后台,下次进来再问。
                        // ponytail: 不做重试计数/锁定计时,失败一律丢回后台就够了
                        if (!activity.moveTaskToBack(true)) {
                            activity.finishAndRemoveTask();
                        }
                    }
                });
    }
}
