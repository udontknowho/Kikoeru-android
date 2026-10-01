package com.zinhao.kikoeru;

import android.app.Activity;
import android.content.Intent;
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
 *
 * 验证由全屏的 {@link LockActivity} 承载:先盖住屏幕,再验证,过了才露出内容。
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
        // 框架版的 BiometricManager 没有 from(Context) 这个静态工厂(那是 androidx 版的),只能从系统服务拿
        BiometricManager manager = App.getInstance().getSystemService(BiometricManager.class);
        if (manager == null) {
            return false;
        }
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

    /**
     * 把全屏遮罩盖上来。验证动作发生在遮罩页里面,所以不用再担心
     * "宿主 Activity 中途 finish 导致弹窗被取消" 那类问题。
     */
    public static void showLockScreen(Activity activity) {
        // LauncherActivity 和 LrcFloatWindow 是"过路"的,起来就 finish:
        // 让下一个稳定的 Activity 来盖遮罩,避开启动期的时序问题
        if (unlocked || prompting || activity.isFinishing()
                || activity instanceof LockActivity
                || activity instanceof LauncherActivity || activity instanceof LrcFloatWindow) {
            return;
        }
        Intent intent = new Intent(activity, LockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.startActivity(intent);
    }

    /** 由遮罩页调用:弹出验证 */
    public static void authenticate(Activity activity) {
        if (unlocked || prompting || activity.isFinishing()) {
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
                        unlock(activity);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        // 指纹和锁屏密码都没了(验证过程中被删掉也一样)。宁可放进去,
                        // 也不把用户锁在自己的 App 外面。
                        if (!isAvailable()) {
                            unlocked = true;
                            prompting = false;
                            Toast.makeText(activity, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show();
                            unlock(activity);
                            return;
                        }
                        // 其余全部按"没过"处理:整个任务丢到后台,下次进来再问。
                        // prompting 保持 true,免得弹窗刚消失又被 onResume 立刻重新弹。
                        // ponytail: 不做重试计数/锁定计时,失败一律丢回后台就够了
                        if (!activity.moveTaskToBack(true)) {
                            activity.finishAndRemoveTask();
                        }
                    }
                });
    }

    /** 验证过了:关掉遮罩页,底下的内容自己就露出来了 */
    private static void unlock(Activity activity) {
        if (activity instanceof LockActivity) {
            activity.finish();
        }
    }
}
