package com.zinhao.kikoeru;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.room.Room;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.zinhao.kikoeru.db.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class App extends Application implements Application.ActivityLifecycleCallbacks {
    private static App instance;
    public static final String ID_PLAY_SERVICE = "com.zinhao.kikoeru.play_control";
    public static final String CONFIG_FILE_NAME = "app.config";
    public static final String CONFIG_UPDATE_TIME = "update_time";
    public static final String CONFIG_USER_DATABASE_ID = "current_user_database_id";
    public static final String CONFIG_LAYOUT_TYPE = "layout_type";
    /** 主题模式：0 跟随系统 1 浅色 2 深色 */
    public static final String CONFIG_NIGHT_MODE = "night_mode";
    public static final String CONFIG_ONLY_DISPLAY_LRC = "only_display_lrc";
    public static final String CONFIG_SORT = "sort";
    public static final String CONFIG_ORDER = "order";
    public static final String CONFIG_DEBUG = "debug";
    public static final String CONFIG_NEW_LAYOUT = "new_layout";
    public static final String CONFIG_SAVE_EXTERNAL = "save_at_external_dir";
    public static final String CONFIG_PROXY_ENABLED = "proxy_enabled";
    public static final String CONFIG_PROXY_ADDR = "proxy_addr";
    public static final String CONFIG_LRC_TEXT_SIZE = "lrc_text_size";
    public static final String CONFIG_LRC_TEXT_COLOR = "lrc_text_color";
    public static final String CONFIG_LRC_TEXT_ALPHA = "lrc_text_alpha";
    public static final String CONFIG_LRC_BG_COLOR = "lrc_bg_color";
    public static final String CONFIG_LRC_BG_ALPHA = "lrc_bg_alpha";
    public static final String DEFAULT_PROXY_ADDR = "127.0.0.1:7890";

    /** 桌面字幕字号(sp),设置页里可调 */
    public int getLrcTextSize() {
        return (int) getValue(CONFIG_LRC_TEXT_SIZE, 36L);
    }

    /** 取主题模式（0 跟随系统 / 1 浅色 / 2 深色） */
    public int getNightMode() {
        return (int) getValue(CONFIG_NIGHT_MODE, 0L);
    }

    /** 设主题模式：存下来并立刻应用（AppCompat 会自己重建界面） */
    public void setNightMode(int mode) {
        setValue(CONFIG_NIGHT_MODE, (long) mode);
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode));
    }

    public static int toNightMode(int mode) {
        if (mode == 1) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        if (mode == 2) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /** 字幕文字颜色(RGB,透明度单独存) */
    public int getLrcTextColor() {
        return (int) getValue(CONFIG_LRC_TEXT_COLOR, 0xFFFFFFL);
    }

    /** 字幕文字透明度 0~255 */
    public int getLrcTextAlpha() {
        return (int) getValue(CONFIG_LRC_TEXT_ALPHA, 255L);
    }

    /** 字幕背景色(RGB),透明度为 0 时就是没背景 */
    public int getLrcBgColor() {
        return (int) getValue(CONFIG_LRC_BG_COLOR, 0x000000L);
    }

    public int getLrcBgAlpha() {
        return (int) getValue(CONFIG_LRC_BG_ALPHA, 0L);
    }

    /**
     * 把桌面字幕的样式一次应用上去(字号/颜色/透明度/背景)。
     * 每次刷新字幕行时调一遍,所以设置改完下一句就生效,不用把 Activity 和 Service 连起来。
     * ponytail: 背景 drawable 每句重建一个,量级是几秒一个对象,不值得为此做缓存
     */
    public void styleLrcFloatText(TextView tv) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, getLrcTextSize());
        tv.setTextColor((getLrcTextAlpha() << 24) | getLrcTextColor());
        int bgAlpha = getLrcBgAlpha();
        if (bgAlpha <= 0) {
            tv.setBackground(null);
            return;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor((bgAlpha << 24) | getLrcBgColor());
        background.setCornerRadius(getResources().getDisplayMetrics().density * 12f);
        tv.setBackground(background);
    }


    /**
     * 把未捕获异常的堆栈写到 /sdcard/Android/media/<包名>/crash/ 下。
     * 这个目录别的 App（包括 Termux）能读，方便直接把日志拿过来定位闪退；
     * 写完仍然交给系统原本的处理器，崩溃对话框不变。
     * 只有开发调试模式才落盘，平时闪退不留文件。
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // 在崩溃时判断而不是安装时判断：设置页里一开关就生效，不用重启
            if (appDebug) {
                try {
                    File[] dirs = getExternalMediaDirs();
                    File dir = new File(dirs != null && dirs.length > 0 ? dirs[0] : getFilesDir(), "crash");
                    if (dir.mkdirs() || dir.isDirectory()) {
                        String stamp = new SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(new Date());
                        File out = new File(dir, "crash-" + stamp + ".txt");
                        PrintWriter pw = new PrintWriter(new FileWriter(out, true));
                        pw.println("time: " + new Date());
                        pw.println("thread: " + thread.getName());
                        pw.println("version: " + BuildConfig.VERSION_NAME);
                        throwable.printStackTrace(pw);
                        pw.close();
                        Log.e("KikoeruCrash", "crash log -> " + out.getAbsolutePath(), throwable);
                    }
                } catch (Throwable ignored) {
                    // 写日志本身失败就算了，不能因此再抛一次
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * 调试模式下的诊断日志（不闪退的问题，比如切账号后白屏，靠它定位）。
     * 写到 /sdcard/Android/media/<包名>/log/app.log —— 这个目录别的 App（包括 Termux）能读。
     * 没开调试模式时直接返回，调用点不用自己判断，也不会留文件。
     */
    public void debugLog(String tag, String msg) {
        if (!appDebug) {
            return;
        }
        Log.d("Kikoeru", "[" + tag + "] " + msg);
        try {
            File[] dirs = getExternalMediaDirs();
            File dir = new File(dirs != null && dirs.length > 0 ? dirs[0] : getFilesDir(), "log");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File out = new File(dir, "app.log");
            // ponytail: 超过 1MB 直接清空重来，不做滚动归档
            if (out.length() > 1024 * 1024) {
                out.delete();
            }
            PrintWriter pw = new PrintWriter(new FileWriter(out, true));
            pw.println(new SimpleDateFormat("MMdd-HHmmss.SSS", java.util.Locale.US).format(new Date())
                    + " [" + tag + "] " + msg);
            pw.close();
        } catch (Throwable ignored) {
            // 写日志失败不能影响主流程
        }
    }

    public static App getInstance() {
        return instance;
    }

    private boolean saveExternal = false;
    private boolean appDebug = false;
    private boolean useNewLayout = false;
    /** 已 started 的 Activity 数,归零说明 App 退到后台了 */
    private int startedActivities = 0;
    /** 退到后台时置位：回到前台后首页要回到“全部作品” */
    private boolean homeNeedsReset = false;


    private final List<User> allUsers = new ArrayList<>();
    private final List<LocalWorkHistory> localWorkHistoryList = new ArrayList<>();
    private long currentUserId;

    private RequestOptions radius15Pic;
    private RequestOptions radius5Pic;
    private RequestOptions noRadiusPic;
    private UserDao userDao;
    private LocalWorkHistoryDao historyDao;
    private AudioLrcBindDao  audioLrcBindDao ;

    private final List<Activity> activities = new ArrayList<>();
    private final HashMap<String,Long> circlesIdMap = new HashMap<>();

    public void setAppDebug(boolean appDebug) {
        this.appDebug = appDebug;
        setValue(CONFIG_DEBUG, appDebug ? 1 : 0);
    }

    public boolean isUseNewLayout() {
        return useNewLayout;
    }

    public void setUseNewLayout(boolean useNewLayout) {
        this.useNewLayout = useNewLayout;
        setValue(CONFIG_NEW_LAYOUT,useNewLayout ? 1:0);
    }

    public boolean isAppDebug() {
        return appDebug;
    }

    public boolean isSaveExternal() {
        return saveExternal;
    }

    public void setSaveExternal(boolean saveExternal) {
        this.saveExternal = saveExternal;
        setValue(CONFIG_SAVE_EXTERNAL, saveExternal ? 1 : 0);
    }

    public long getCurrentUserId() {
        return currentUserId;
    }

    public void setCurrentUserId(long currentUserId) {
        this.currentUserId = currentUserId;
    }

    public long mapCirclesId(String circlesName){
        if(circlesIdMap.containsKey(circlesName)){
            final Long id = circlesIdMap.get(circlesName);
            if(id == null){
                return -1;
            }
            return id;
        }
        return -1;
    }

    public void initCirclesIdMap(JSONArray circlesList) throws JSONException {
        /***
         *  {
         *         "id": 54978,
         *         "name": "#ハチゼロニ",
         *         "count": 2
         *     },
         */
        for (int i = 0; i < circlesList.length(); i++) {
            JSONObject j = circlesList.getJSONObject(i);
            circlesIdMap.put(j.getString("name"),j.getLong("id"));
        }
    }

    public HashMap<String, Long> getCirclesIdMap() {
        return circlesIdMap;
    }

    public RequestOptions getRadius15Pic() {
        return radius15Pic;
    }

    public RequestOptions getRadius5Pic() {
        return radius5Pic;
    }

    public RequestOptions getNoRadiusPic() {
        return noRadiusPic;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        installCrashLogger();
        // 主题模式要在任何 Activity 创建前应用
        AppCompatDelegate.setDefaultNightMode(toNightMode((int) getValue(CONFIG_NIGHT_MODE, 0)));
        registerActivityLifecycleCallbacks(this);
        AppDatabase appDatabase = Room.databaseBuilder(getApplicationContext(), AppDatabase.class,"app.db")
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .addMigrations(AppDatabase.MIGRATION_2_3)
                .addMigrations(AppDatabase.MIGRATION_3_4)
                .build();
        userDao = appDatabase.userDao();
        historyDao = appDatabase.historyDao();
        audioLrcBindDao = appDatabase.audioLrcBindDao();

        currentUserId = getValue(App.CONFIG_USER_DATABASE_ID, -1);
        appDebug = getValue(App.CONFIG_DEBUG, 0) == 1;
        useNewLayout = getValue(App.CONFIG_NEW_LAYOUT,0) == 1;
        saveExternal = getValue(App.CONFIG_SAVE_EXTERNAL, 0) == 1;
        loadLocalHis();
        User user = App.getInstance().currentUser();
        if (user != null) {
            Api.init(user.getToken(), user.getHost());
        }



        radius15Pic = new RequestOptions().placeholder(R.drawable.ic_no_cover).apply(RequestOptions.bitmapTransform(
                new RoundedCorners((int) dp2px(15.0f,getResources().getDisplayMetrics()))
        ));
        radius5Pic = new RequestOptions().placeholder(R.drawable.ic_no_cover).apply(RequestOptions.bitmapTransform(
                new RoundedCorners((int) dp2px(5.0f,getResources().getDisplayMetrics()))
        ));
        noRadiusPic =  new RequestOptions().placeholder(R.drawable.ic_no_cover);

        DownloadUtils.getInstance().init(this);
        NotificationChannel channelMusicService =
                new NotificationChannel(
                        ID_PLAY_SERVICE,
                        getString(R.string.channel_description), NotificationManager.IMPORTANCE_LOW
                );
        channelMusicService.setDescription(getString(R.string.channel_description));
        channelMusicService.enableLights(false);
        channelMusicService.enableVibration(false);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channelMusicService);
    }

    private static float dp2px(float dp, DisplayMetrics displayMetrics){
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,dp,displayMetrics);
    }

    public boolean noActiveActivity(){
        return activities.isEmpty();
    }

    public void alertException(Exception e) {
        if (activities.isEmpty())
            return;
        Activity activity = activities.get(activities.size() - 1);
        if (activity == null) {
            return;
        }
        if (activity instanceof BaseActivity) {
            ((BaseActivity) activity).alertException(e);
        }
    }

    public static JSONArray getTagsList(JSONObject jsonObject) throws JSONException {
        return jsonObject.getJSONArray("tags");
    }

    public static JSONArray getVasList(JSONObject jsonObject) throws JSONException {
        return jsonObject.getJSONArray("vas");
    }

    public void setValue(String key, String value) {
        SharedPreferences sharedPreferences = getSharedPreferences(CONFIG_FILE_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(key, value);
        editor.apply();
    }

    public void setValue(String key, long value) {
        SharedPreferences sharedPreferences = getSharedPreferences(CONFIG_FILE_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(key, value);
        editor.apply();
    }

    public String getValue(String key, String def) {
        SharedPreferences sharedPreferences = getSharedPreferences(CONFIG_FILE_NAME, MODE_PRIVATE);
        return sharedPreferences.getString(key, def);
    }

    public long getValue(String key, long def) {
        SharedPreferences sharedPreferences = getSharedPreferences(CONFIG_FILE_NAME, MODE_PRIVATE);
        return sharedPreferences.getLong(key, def);
    }

    public void savePosition(float x, float y) {
        SharedPreferences sharedPreferences = getSharedPreferences(CONFIG_FILE_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putFloat("WINDOW_X", x);
        editor.putFloat("WINDOW_Y", y);
        editor.apply();
    }

    public float[] getPosition() {
        float[] position = new float[2];
        SharedPreferences sharedPreferences = getSharedPreferences(CONFIG_FILE_NAME, MODE_PRIVATE);
        position[0] = sharedPreferences.getFloat("WINDOW_X", 145);
        position[1] = sharedPreferences.getFloat("WINDOW_Y", 160);
        return position;
    }

    public void insertLocalHis(LocalWorkHistory history, Runnable callback){
        LocalFileCache.getInstance().doSomething(()->{
            historyDao.insertOrReplace(history);
            boolean sameWorkRj = false;
            if(!localWorkHistoryList.isEmpty()){
                if(history.getRjNumber() == localWorkHistoryList.get(0).getRjNumber()){
                    sameWorkRj = true;
                }
            }
            if(!sameWorkRj){
                localWorkHistoryList.add(0,history);
            }
            callback.run();
        });
    }

    public void loadLocalHis(){
        LocalFileCache.getInstance().doSomething(()->{
            localWorkHistoryList.clear();
            localWorkHistoryList.addAll(historyDao.getAllHis());
            Log.i("App","getLocalWorkHistoryList:" + localWorkHistoryList.size());
        });
    }

    public List<LocalWorkHistory> getLocalWorkHistoryList() {
        return localWorkHistoryList;
    }

    public void insertUser(User user, Runnable callback) {
        LocalFileCache.getInstance().doSomething(()->{
            currentUserId = userDao.insert(user);
            user.setId(currentUserId);
            allUsers.add(user);
            callback.run();
        });
    }

    public void deleteUser(User user) {
        allUsers.remove(user);
        LocalFileCache.getInstance().doSomething(()->{
            userDao.delete(user);
        });
    }

    public void updateUser(User user) {
        LocalFileCache.getInstance().doSomething(()->{
            userDao.update(user);
        });
    }

    public void getAllUsersAsync(DatabaseResultCallback databaseResultCallback) {
        LocalFileCache.getInstance().doSomething(()->{
            List<User> result = userDao.getAllUser();
            allUsers.clear();
            allUsers.addAll(result);
            databaseResultCallback.onResult(result);
        });
    }

    public List<User> getAllUsers(){
        return allUsers;
    }

    public User currentUser() {
        for (User user : allUsers) {
            if(user.getId() == null){
                continue;
            }
            if (user.getId().equals(currentUserId)) {
                return user;
            }
        }
        return null;
    }

    public void insertLrcBind(AudioLrcBind lrcBind) {
        LocalFileCache.getInstance().doSomething(()->{
            audioLrcBindDao.insertLrcBind(lrcBind);
        });
    }

    public void getLrcBind(long rjNumber,String audioPath,DatabaseResultCallback callback) {
        LocalFileCache.getInstance().doSomething(()->{
            List<AudioLrcBind> rl = audioLrcBindDao.getLrcBind(rjNumber,audioPath);
            if(!rl.isEmpty()){
                callback.onResult(rl.get(0));
            }
        });
    }

    public interface DatabaseResultCallback {
        void onResult(Object result);
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        activities.add(activity);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        startedActivities++;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (AppLock.isEnabled() && !AppLock.isUnlocked()) {
            AppLock.showLockScreen(activity);
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {

    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (--startedActivities <= 0) {
            startedActivities = 0;
            AppLock.onBackground();
            // 从后台回来就算“重新进 App”：首页不要停在上次点的标签/声优筛选上
            homeNeedsReset = true;
        }
    }

    /** 取出（并清掉）“回前台要重置首页”的标记 */
    public boolean consumeHomeNeedsReset() {
        boolean value = homeNeedsReset;
        homeNeedsReset = false;
        return value;
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        activities.remove(activity);
    }
}
