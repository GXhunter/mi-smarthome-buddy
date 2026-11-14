package io.github.hunter.mihomepurifier;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class MiHomeModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.xiaomi.smarthome";
    static final String TAG = "AdHunter";
    private static final long MAX_BUNDLE_BYTES = 16 * 1024 * 1024;
    private String mProcessName;
    private boolean mMaimaiHookAttempted;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        mProcessName = param.getProcessName();
        // 电饭煲在独立 RN 插件进程运行；推送及摄像头进程不加载规则。
        if (!isTargetProcess(TARGET_PACKAGE) && !isTargetProcess(RailwayHooks.PACKAGE)
                && !isTargetProcess(MaimaiSplashRule.PACKAGE)) {
            detach();
            return;
        }
        log(Log.INFO, TAG, "ad-hunter " + BuildConfig.VERSION_NAME + " 已加载，"
                + getFrameworkName() + " " + getFrameworkVersion() + "，Xposed API " + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (!param.isFirstPackage() || !isTargetProcess(packageName)) {
            return;
        }
        ClassLoader loader = param.getClassLoader();
        if (RailwayHooks.PACKAGE.equals(packageName)) {
            RailwayHooks.install(this, loader);
            return;
        }
        if (MaimaiSplashRule.PACKAGE.equals(packageName)) {
            if (!mMaimaiHookAttempted) {
                mMaimaiHookAttempted = true;
                MaimaiSplashRule.attach(this, param);
            }
            return;
        }
        defaultCookerDeviceTab(loader);
        if (!TARGET_PACKAGE.equals(mProcessName)) {
            return;
        }
        hideProductTab(loader);
        hideMallCenter(loader);
        var app = param.getApplicationInfo();
        try {
            // 用创建前的宿主 Context 访问代码缓存，并在业务首次调用前安装规则。
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
                            long started = android.os.SystemClock.elapsedRealtime();
                            var targets = MiHomeDex.findCached(app.sourceDir, app.splitSourceDirs,
                                    context.getCodeCacheDir().toPath());
                            log(Log.INFO, TAG, "米家广告入口解析耗时 "
                                    + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
                            disableAds(loader, targets.policy());
                            skipSplashAdWait(loader, targets.adPrepared(), targets.consent());
                        } catch (IOException | RuntimeException e) {
                            log(Log.WARN, TAG, "广告策略和开屏入口无法定位：" + e.getClass().getSimpleName());
                        }
                    }
                    return chain.proceed();
                }
            };
            startup.mHandle = hook(newApplication).intercept(startup);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "广告策略和开屏初始化入口不兼容", e);
        }
    }

    private boolean isTargetProcess(String packageName) {
        if (TARGET_PACKAGE.equals(packageName)) {
            return TARGET_PACKAGE.equals(mProcessName)
                    || mProcessName.matches("com\\.xiaomi\\.smarthome:plugin[0-4]");
        }
        return (RailwayHooks.PACKAGE.equals(packageName) || MaimaiSplashRule.PACKAGE.equals(packageName))
                && packageName.equals(mProcessName);
    }

    private void hideMallCenter(ClassLoader loader) {
        try {
            Class<?> page = loader.loadClass("com.xiaomi.smarthome.miio.page.SettingMainPageV2");
            Class<?> rowType = loader.loadClass("com.xiaomi.smarthome.miio.page.ItemOptionView");
            hook(requireSignature(page.getDeclaredMethod("onViewCreated", View.class, Bundle.class),
                    void.class, false))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        View mall = findMallRow((View) chain.getArg(0), rowType);
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

    private static View findMallRow(View root, Class<?> rowType) {
        ArrayDeque<View> views = new ArrayDeque<>();
        views.add(root);
        View match = null;
        while (!views.isEmpty()) {
            View view = views.removeFirst();
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    views.add(group.getChildAt(i));
                }
            } else if (view instanceof TextView title && ("商城中心".contentEquals(title.getText())
                    || "Store".contentEquals(title.getText()))) {
                for (var parent = title.getParent(); parent instanceof View row && row != root;
                     parent = row.getParent()) {
                    if (rowType.isInstance(row) && row.isClickable()) {
                        if (match != null && match != row) {
                            return null;
                        }
                        match = row;
                        break;
                    }
                }
            }
        }
        return match;
    }

    private void disableAds(ClassLoader loader, MiHomeDex.MethodId target) {
        if (target == null) {
            log(Log.WARN, TAG, "广告策略入口缺失或不唯一");
            return;
        }
        HookHandle policyHook = null;
        boolean verified = false;
        try {
            Class<?> policy = loader.loadClass(target.owner());
            Method parse = requireSignature(policy.getDeclaredMethod(target.name(), String.class), policy, true);
            parse.setAccessible(true);
            String sample = "{\"code\":0,\"data\":{\"bottomSwitch\":1,"
                    + "\"screenLoadSwitch\":2,\"bannerLoadSwitch\":3,\"contentDisplaySwitch\":4,"
                    + "\"startSwitch\":5,\"personalCenterSwitch\":6,\"resourceLoadTime\":3000}}";
            Object original = parse.invoke(null, sample);
            Object baseline = parse.invoke(null, "{\"code\":0,\"data\":{}}");
            Field[] switches = new Field[6];
            Field wait = null;
            for (Field field : policy.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                    continue;
                }
                if (field.getType() != int.class && field.getType() != long.class) {
                    continue;
                }
                field.setAccessible(true);
                if (field.getType() == int.class) {
                    int slot = field.getInt(original) - 1;
                    if (slot >= 0 && slot < switches.length && field.getInt(baseline) == 0) {
                        if (switches[slot] != null) {
                            throw new IllegalStateException("广告开关字段不唯一");
                        }
                        switches[slot] = field;
                    }
                } else if (field.getType() == long.class
                        && field.getLong(original) == 3000L && field.getLong(baseline) == 0L) {
                    if (wait != null) {
                        throw new IllegalStateException("广告等待字段不唯一");
                    }
                    wait = field;
                }
            }
            Field loadTime = wait;
            if (loadTime == null || Arrays.asList(switches).contains(null)) {
                throw new IllegalStateException("广告策略字段语义已变化");
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

    private void skipSplashAdWait(ClassLoader loader, MiHomeDex.MethodId event, MiHomeDex.MethodId consent) {
        if (event == null || consent == null) {
            log(Log.WARN, TAG, "开屏广告事件或隐私判断入口缺失或不唯一");
            return;
        }
        HookHandle eventHook = null;
        HookHandle constructorHook = null;
        boolean installed = false;
        try {
            Class<?> splash = loader.loadClass(event.owner());
            Method adPrepared = requireSignature(splash.getDeclaredMethod(event.name(), boolean.class),
                    void.class, false);
            Method consented = requireSignature(loader.loadClass(consent.owner()).getDeclaredMethod(consent.name()),
                    boolean.class, true);
            adPrepared.setAccessible(true);
            consented.setAccessible(true);
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

    private void hideProductTab(ClassLoader loader) {
        try {
            Class<?> tabIdType = loader.loadClass("com.xiaomi.smarthome.tab.TabId");
            Object brand = tabIdType.getField("Brand").get(null);
            Class<?> tabView = loader.loadClass(
                    "com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew$TabView").asSubclass(View.class);
            Method setter = null;
            Field id = null;
            for (Method method : tabView.getDeclaredMethods()) {
                if (method.getName().equals("setTabInfo") && method.getReturnType() == void.class
                        && !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1) {
                    for (Field field : method.getParameterTypes()[0].getDeclaredFields()) {
                        if (field.getType() == tabIdType && !Modifier.isStatic(field.getModifiers())) {
                            if (id != null) {
                                throw new IllegalStateException("产品 Tab 标识字段不唯一");
                            }
                            setter = method;
                            id = field;
                        }
                    }
                }
            }
            if (id == null) {
                throw new IllegalStateException("产品 Tab 标识字段缺失");
            }
            Field tabId = id;
            tabId.setAccessible(true);
            hook(setter).intercept(chain -> {
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
                Path target;
                try {
                    Path original = Paths.get(source);
                    Path manifest = original.getParent().getParent().resolve("model_list.json");
                    if (!Files.isRegularFile(manifest) || Files.size(manifest) > 64 * 1024
                            || Files.size(original) > MAX_BUNDLE_BYTES) {
                        target = null;
                    } else {
                        target = patchCookerBundle(original, manifest);
                    }
                } catch (IOException | JSONException | RuntimeException e) {
                    log(Log.ERROR, TAG, "电饭煲补丁失败，加载原插件：" + e.getClass().getSimpleName());
                    target = null;
                }
                if (target == null) {
                    return chain.proceed();
                }
                Object[] args = chain.getArgs().toArray();
                args[0] = target.toString();
                log(Log.INFO, TAG, "电饭煲默认设备页补丁已应用");
                // 资源基址（第二个参数）仍指向原目录；原加载失败时不重复执行。
                return chain.proceed(args);
            });
            log(Log.INFO, TAG, "电饭煲默认设备页规则已注册");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log(Log.ERROR, TAG, "电饭煲规则不兼容，保留原页面", e);
        }
    }

    private static Path patchCookerBundle(Path original, Path manifest) throws IOException, JSONException {
        JSONArray models = new JSONObject(new String(Files.readAllBytes(manifest),
                StandardCharsets.UTF_8)).getJSONArray("model_list");
        if (models.length() == 0) {
            return null;
        }
        for (int i = 0; i < models.length(); i++) {
            if (!CookerBundlePatch.isCookerModel(models.getString(i))) {
                return null;
            }
        }
        byte[] patched = CookerBundlePatch.patch(Files.readAllBytes(original));
        Path target = original.resolveSibling("mihome-purifier-device-v1.bundle");
        Path temporary = Files.createTempFile(original.getParent(), "mihome-purifier-", ".tmp");
        try {
            Files.write(temporary, patched);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
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
