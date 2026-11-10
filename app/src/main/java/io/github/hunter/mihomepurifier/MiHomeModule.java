package io.github.hunter.mihomepurifier;

import android.util.Log;
import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import io.github.libxposed.api.XposedModule;

public final class MiHomeModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.xiaomi.smarthome";
    private static final String TAG = "MiHomePurifier";
    private boolean mMainProcess;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        mMainProcess = TARGET_PACKAGE.equals(param.getProcessName());
        // 电饭煲在独立 RN 插件进程运行；推送及摄像头进程不加载规则。
        if (!mMainProcess && !param.getProcessName().matches("com\\.xiaomi\\.smarthome:plugin[0-4]")) {
            detach();
            return;
        }
        log(Log.INFO, TAG, "米家净化已加载，Xposed API " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        ClassLoader loader = param.getClassLoader();
        if (mMainProcess) {
            hideProductTab(loader);
            hideMallCenter(loader);
            disableAds(loader);
        }
        defaultCookerDeviceTab(loader);
    }

    private void hideMallCenter(ClassLoader loader) {
        try {
            Class<?> page = loader.loadClass("com.xiaomi.smarthome.miio.page.SettingMainPageV2");
            Field mallField = page.getDeclaredField("Oooo0o");
            mallField.setAccessible(true);
            hook(page.getDeclaredMethod("onViewCreated", View.class, Bundle.class))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        // 米家 11.8.707 的商城中心整行，避免隐藏文字后留下可点击的空白。
                        View mall = (View) mallField.get(chain.getThisObject());
                        if (mall != null) {
                            mall.setVisibility(View.GONE);
                            log(Log.INFO, TAG, "我的页面商城中心已隐藏");
                        } else {
                            log(Log.WARN, TAG, "未找到商城中心入口，保留原页面");
                        }
                        return result;
                    });
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "商城中心隐藏规则不兼容", e);
        }
    }

    private void disableAds(ClassLoader loader) {
        try {
            Class<?> policy = loader.loadClass("_m_j.y64");
            // 米家 11.8.707：底部、实时开屏、横幅、内容、开屏展示、个人中心。
            String[] names = {"OooO0O0", "OooO0o0", "OooO0o", "OooO0oO", "OooO0oo", "OooO"};
            Field[] switches = new Field[names.length];
            for (int i = 0; i < names.length; i++) {
                switches[i] = policy.getDeclaredField(names[i]);
                switches[i].setAccessible(true);
                if (switches[i].getType() != int.class) {
                    throw new IllegalStateException("广告策略字段类型已变化");
                }
            }
            Field loadTime = policy.getDeclaredField("OooO00o");
            loadTime.setAccessible(true);
            if (loadTime.getType() != long.class) {
                throw new IllegalStateException("广告等待字段类型已变化");
            }
            Method parse = policy.getDeclaredMethod("OooO00o", String.class);
            hook(parse).intercept(chain -> {
                Object result = chain.proceed();
                for (Field field : switches) {
                    field.setInt(result, 0);
                }
                loadTime.setLong(result, 0L);
                return result;
            });
            // 用宿主实际解析器自检，确认服务端全部开启时也会被覆盖。
            Object checked = parse.invoke(null, "{\"code\":0,\"data\":{\"bottomSwitch\":1,"
                    + "\"screenLoadSwitch\":1,\"bannerLoadSwitch\":1,\"contentDisplaySwitch\":1,"
                    + "\"startSwitch\":1,\"personalCenterSwitch\":1,\"resourceLoadTime\":3000}}");
            for (Field field : switches) {
                if (field.getInt(checked) != 0) {
                    throw new IllegalStateException("广告策略自检失败：" + field.getName());
                }
            }
            if (loadTime.getLong(checked) != 0L) {
                throw new IllegalStateException("广告等待自检失败");
            }
            log(Log.INFO, TAG, "广告策略自检通过：六类广告开关及加载等待均为 0");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "广告策略规则不兼容", e);
        }
        try {
            Class<?> splash = loader.loadClass("_m_j.sz8");
            Method adPrepared = splash.getDeclaredMethod("OooO00o", boolean.class);
            hook(adPrepared).intercept(chain -> chain.proceed(new Object[]{false}));
            Method consented = loader.loadClass("_m_j.in1").getDeclaredMethod("OooO0o");
            hook(splash.getDeclaredConstructor(loader.loadClass("com.xiaomi.smarthome.SmartHomeMainActivity")))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        // 已同意隐私时预先通知无广告，跳过广告准备等待，仍由首页就绪解除开屏。
                        if (Boolean.TRUE.equals(consented.invoke(null))) {
                            adPrepared.invoke(chain.getThisObject(), false);
                            log(Log.INFO, TAG, "开屏已跳过广告等待，保留首页就绪条件");
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "开屏广告等待规则已注册");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "开屏广告等待规则不兼容", e);
        }
    }

    private void hideProductTab(ClassLoader loader) {
        try {
            Class<?> tabInfo = loader.loadClass("_m_j.j87");
            Field tabId = tabInfo.getDeclaredField("OooO00o");
            tabId.setAccessible(true);
            Class<?> tabView = loader.loadClass(
                    "com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew$TabView");
            hook(tabView.getDeclaredMethod("setTabInfo", tabInfo)).intercept(chain -> {
                Object result = chain.proceed();
                if ("Brand".equals(((Enum<?>) tabId.get(chain.getArg(0))).name())) {
                    // 保留原索引，避免其余入口的点击与页面映射错位。
                    ((View) chain.getThisObject()).setVisibility(View.GONE);
                }
                return result;
            });
            log(Log.INFO, TAG, "产品 Tab 隐藏规则已注册");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "产品 Tab 规则不兼容，保留原界面", e);
        }
    }

    private void defaultCookerDeviceTab(ClassLoader loader) {
        try {
            Class<?> catalyst = loader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl");
            hook(catalyst.getDeclaredMethod("loadScriptFromFile", String.class, String.class,
                    boolean.class)).intercept(chain -> {
                String source = (String) chain.getArg(0);
                if (!CookerBundlePatch.matchesPath(source)) {
                    return chain.proceed();
                }
                Object[] args = chain.getArgs().toArray();
                try {
                    Path original = Paths.get(source);
                    byte[] patched = CookerBundlePatch.patch(Files.readAllBytes(original));
                    Path target = original.resolveSibling("mihome-purifier-device-v1.bundle");
                    Path temporary = Files.createTempFile(original.getParent(), "mihome-purifier-", ".tmp");
                    try {
                        Files.write(temporary, patched);
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                    args[0] = target.toString();
                    log(Log.INFO, TAG, "电饭煲默认设备页补丁已应用");
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "电饭煲补丁失败，加载原插件", e);
                }
                // 资源基址（第二个参数）仍指向原目录；原加载失败时不重复执行。
                return chain.proceed(args);
            });
            log(Log.INFO, TAG, "电饭煲默认设备页规则已注册");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "电饭煲规则不兼容，保留原页面", e);
        }
    }
}
