package io.github.hunter.mihomepurifier;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class MiHomeModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.xiaomi.smarthome";
    static final String TAG = "AdHunter";
    private static final long MI_HOME_11_8_707 = 110817071;
    private static final long MI_HOME_11_9_705 = 110917051;
    private static final long RAILWAY_5_9_6_8 = 280;
    private String mProcessName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        mProcessName = param.getProcessName();
        // 电饭煲在独立 RN 插件进程运行；推送及摄像头进程不加载规则。
        if (!isTargetProcess(TARGET_PACKAGE) && !isTargetProcess(RailwayHooks.PACKAGE)) {
            detach();
            return;
        }
        log(Log.INFO, TAG, "ad-hunter " + BuildConfig.VERSION_NAME + " 已加载，"
                + getFrameworkName() + " " + getFrameworkVersion() + "，Xposed API " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (!isTargetProcess(packageName)) {
            return;
        }
        ClassLoader loader = param.getClassLoader();
        try {
            // 包就绪时 Application 尚未创建。用创建前的宿主 Context 查询版本。
            Method newApplication = Instrumentation.class.getDeclaredMethod(
                    "newApplication", ClassLoader.class, String.class, Context.class);
            var startup = new Hooker() {
                private HookHandle mHandle;

                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Context context = (Context) chain.getArg(2);
                    if (chain.getArg(0) == loader && packageName.equals(context.getPackageName())) {
                        mHandle.unhook();
                        try {
                            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
                            long version = Build.VERSION.SDK_INT >= 28
                                    ? info.getLongVersionCode() : info.versionCode;
                            installForVersion(packageName, loader, version);
                        } catch (PackageManager.NameNotFoundException | SecurityException e) {
                            log(Log.WARN, TAG, "宿主版本无法核对，跳过规则");
                        }
                    }
                    return chain.proceed();
                }
            };
            startup.mHandle = hook(newApplication).intercept(startup);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "宿主版本检查入口不兼容", e);
        }
    }

    private boolean isTargetProcess(String packageName) {
        if (TARGET_PACKAGE.equals(packageName)) {
            return TARGET_PACKAGE.equals(mProcessName)
                    || mProcessName.matches("com\\.xiaomi\\.smarthome:plugin[0-4]");
        }
        return RailwayHooks.PACKAGE.equals(packageName) && RailwayHooks.PACKAGE.equals(mProcessName);
    }

    private void installForVersion(String packageName, ClassLoader loader, long version) {
        if (RailwayHooks.PACKAGE.equals(packageName) && version == RAILWAY_5_9_6_8) {
            RailwayHooks.install(this, loader);
        } else if (TARGET_PACKAGE.equals(packageName)
                && (version == MI_HOME_11_8_707 || version == MI_HOME_11_9_705)) {
            if (TARGET_PACKAGE.equals(mProcessName)) {
                boolean newer = version == MI_HOME_11_9_705;
                hideProductTab(loader, newer ? "_m_j.ha7" : "_m_j.j87");
                hideMallCenter(loader, newer ? 2131433041 : 2131432991);
                disableAds(loader, newer ? "_m_j.z74" : "_m_j.y64");
                skipSplashAdWait(loader, newer ? "_m_j.s19" : "_m_j.sz8",
                        newer ? "_m_j.eo1" : "_m_j.in1");
            }
            defaultCookerDeviceTab(loader);
        } else {
            log(Log.WARN, TAG, "宿主版本未适配，保留原行为：" + packageName + " " + version);
        }
    }

    private void hideMallCenter(ClassLoader loader, int mallId) {
        try {
            Class<?> page = loader.loadClass("com.xiaomi.smarthome.miio.page.SettingMainPageV2");
            hook(requireSignature(page.getDeclaredMethod("onViewCreated", View.class, Bundle.class),
                    void.class, false))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        View mall = ((View) chain.getArg(0)).findViewById(mallId);
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

    private void disableAds(ClassLoader loader, String policyClass) {
        HookHandle policyHook = null;
        boolean verified = false;
        try {
            Class<?> policy = loader.loadClass(policyClass);
            // 底部、实时开屏、横幅、内容、开屏展示、个人中心。
            String[] names = {"OooO0O0", "OooO0o0", "OooO0o", "OooO0oO", "OooO0oo", "OooO"};
            Field[] switches = new Field[names.length];
            for (int i = 0; i < names.length; i++) {
                switches[i] = policy.getDeclaredField(names[i]);
                switches[i].setAccessible(true);
                if (switches[i].getType() != int.class
                        || Modifier.isStatic(switches[i].getModifiers())
                        || Modifier.isFinal(switches[i].getModifiers())) {
                    throw new IllegalStateException("广告策略字段类型已变化");
                }
            }
            Field loadTime = policy.getDeclaredField("OooO00o");
            loadTime.setAccessible(true);
            if (loadTime.getType() != long.class || Modifier.isStatic(loadTime.getModifiers())
                    || Modifier.isFinal(loadTime.getModifiers())) {
                throw new IllegalStateException("广告等待字段类型已变化");
            }
            Method parse = requireSignature(policy.getDeclaredMethod("OooO00o", String.class), policy, true);
            String sample = "{\"code\":0,\"data\":{\"bottomSwitch\":1,"
                    + "\"screenLoadSwitch\":2,\"bannerLoadSwitch\":3,\"contentDisplaySwitch\":4,"
                    + "\"startSwitch\":5,\"personalCenterSwitch\":6,\"resourceLoadTime\":3000}}";
            Object original = parse.invoke(null, sample);
            for (int i = 0; i < switches.length; i++) {
                if (switches[i].getInt(original) != i + 1) {
                    throw new IllegalStateException("广告策略字段语义已变化");
                }
            }
            if (loadTime.getLong(original) != 3000L) {
                throw new IllegalStateException("广告等待字段语义已变化");
            }
            policyHook = hook(parse).intercept(chain -> {
                Object result = chain.proceed();
                if (policy.isInstance(result)) {
                    for (Field field : switches) {
                        field.setInt(result, 0);
                    }
                    loadTime.setLong(result, 0L);
                }
                return result;
            });
            Object checked = parse.invoke(null, sample);
            for (Field field : switches) {
                if (field.getInt(checked) != 0) {
                    throw new IllegalStateException("广告策略自检失败：" + field.getName());
                }
            }
            if (loadTime.getLong(checked) != 0L) {
                throw new IllegalStateException("广告等待自检失败");
            }
            verified = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "广告策略规则不兼容", e);
        } finally {
            if (!verified) {
                unhookInstalled(this, policyHook);
            }
        }
        if (verified) {
            log(Log.INFO, TAG, "广告策略自检通过：六类广告开关及加载等待均为 0");
        }
    }

    private void skipSplashAdWait(ClassLoader loader, String splashClass, String consentClass) {
        HookHandle eventHook = null;
        HookHandle constructorHook = null;
        boolean installed = false;
        try {
            Class<?> splash = loader.loadClass(splashClass);
            Method adPrepared = requireSignature(splash.getDeclaredMethod("OooO00o", boolean.class),
                    void.class, false);
            Method consented = requireSignature(loader.loadClass(consentClass).getDeclaredMethod("OooO0o"),
                    boolean.class, true);
            Constructor<?> constructor = splash.getDeclaredConstructor(
                    loader.loadClass("com.xiaomi.smarthome.SmartHomeMainActivity"));
            eventHook = hook(adPrepared).intercept(chain -> {
                if (Boolean.TRUE.equals(chain.getArg(0)) && Boolean.TRUE.equals(consented.invoke(null))) {
                    return chain.proceed(new Object[]{false});
                }
                return chain.proceed();
            });
            constructorHook = hook(constructor)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        // 已同意隐私时预先通知无广告，跳过广告准备等待，仍由首页就绪解除开屏。
                        if (Boolean.TRUE.equals(consented.invoke(null))) {
                            adPrepared.invoke(chain.getThisObject(), false);
                            log(Log.INFO, TAG, "开屏已跳过广告等待，保留首页就绪条件");
                        }
                        return result;
                    });
            installed = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "开屏广告等待规则不兼容", e);
        } finally {
            if (!installed) {
                unhookInstalled(this, constructorHook, eventHook);
            }
        }
    }

    private void hideProductTab(ClassLoader loader, String tabInfoClass) {
        try {
            Class<?> tabInfo = loader.loadClass(tabInfoClass);
            Field tabId = tabInfo.getDeclaredField("OooO00o");
            Class<?> tabIdType = loader.loadClass("com.xiaomi.smarthome.tab.TabId");
            if (tabId.getType() != tabIdType || Modifier.isStatic(tabId.getModifiers())) {
                throw new IllegalStateException("产品 Tab 标识字段已变化");
            }
            Object brand = tabIdType.getField("Brand").get(null);
            tabId.setAccessible(true);
            Class<?> tabView = loader.loadClass(
                    "com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew$TabView").asSubclass(View.class);
            Method setTabInfo = requireSignature(tabView.getDeclaredMethod("setTabInfo", tabInfo), void.class, false);
            hook(setTabInfo).intercept(chain -> {
                Object result = chain.proceed();
                Object info = chain.getArg(0);
                if (info != null && tabId.get(info) == brand) {
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
            Method loadScript = requireSignature(catalyst.getDeclaredMethod(
                    "loadScriptFromFile", String.class, String.class, boolean.class), void.class, false);
            hook(loadScript).intercept(chain -> {
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
                } catch (IOException | RuntimeException e) {
                    log(Log.ERROR, TAG, "电饭煲补丁失败，加载原插件：" + e.getClass().getSimpleName());
                }
                // 资源基址（第二个参数）仍指向原目录；原加载失败时不重复执行。
                return chain.proceed(args);
            });
            log(Log.INFO, TAG, "电饭煲默认设备页规则已注册");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "电饭煲规则不兼容，保留原页面", e);
        }
    }

    static Method requireSignature(Method method, Class<?> result, boolean isStatic)
            throws NoSuchMethodException {
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException(method.toString());
        }
        return method;
    }

    static void unhookInstalled(XposedInterface module, HookHandle... handles) {
        for (HookHandle handle : handles) {
            if (handle != null) {
                try {
                    handle.unhook();
                } catch (RuntimeException e) {
                    module.log(Log.ERROR, TAG, "Hook 回退失败，重启宿主进程可清理：" + handle.getExecutable(), e);
                }
            }
        }
    }
}
